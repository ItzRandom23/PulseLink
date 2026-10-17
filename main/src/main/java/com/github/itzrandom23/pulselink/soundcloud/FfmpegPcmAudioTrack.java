package com.github.itzrandom23.pulselink.soundcloud;

import com.sedmelluq.discord.lavaplayer.filter.AudioPipeline;
import com.sedmelluq.discord.lavaplayer.filter.AudioPipelineFactory;
import com.sedmelluq.discord.lavaplayer.filter.PcmFormat;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Decodes SoundCloud's fragmented MP4 HLS through FFmpeg and seeks by
 * restarting FFmpeg at the requested media timestamp.
 */
final class FfmpegPcmAudioTrack extends BaseAudioTrack {
    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNEL_COUNT = 2;

    private final String ffmpegPath;
    private final String streamUrl;
    private volatile FfmpegPcmStream activeStream;
    private volatile boolean processingLoopActive;

    FfmpegPcmAudioTrack(AudioTrackInfo trackInfo, String ffmpegPath, String streamUrl) {
        super(trackInfo);
        this.ffmpegPath = ffmpegPath;
        this.streamUrl = streamUrl;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        AudioPipeline pipeline = AudioPipelineFactory.create(
            executor.getProcessingContext(),
            new PcmFormat(CHANNEL_COUNT, SAMPLE_RATE)
        );
        FfmpegPcmStream stream = new FfmpegPcmStream(this.ffmpegPath, this.streamUrl);
        this.activeStream = stream;
        try {
            stream.startAt(0);
            this.processingLoopActive = true;
            executor.executeProcessingLoop(
                () -> stream.provideFrames(pipeline),
                timecode -> {
                    long seekPosition = Math.max(0, timecode);
                    if (this.trackInfo.length > 0) {
                        seekPosition = Math.min(seekPosition, this.trackInfo.length);
                    }
                    stream.restartAt(seekPosition);
                    pipeline.seekPerformed(seekPosition, seekPosition);
                }
            );
        } finally {
            this.processingLoopActive = false;
            this.activeStream = null;
            stream.close();
            pipeline.close();
        }
    }

    @Override
    public void setPosition(long position) {
        FfmpegPcmStream stream = this.activeStream;
        Process processBeingRead = this.processingLoopActive && stream != null
            ? stream.currentProcess()
            : null;
        super.setPosition(position);
        if (processBeingRead != null && stream != null) {
            stream.interruptForSeek(processBeingRead);
        }
    }

    @Override
    public void stop() {
        super.stop();
        FfmpegPcmStream stream = this.activeStream;
        if (stream != null) stream.close();
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new FfmpegPcmAudioTrack(this.trackInfo, this.ffmpegPath, this.streamUrl);
    }

    private static final class FfmpegPcmStream {
        private static final int MAX_DIAGNOSTIC_BYTES = 16 * 1024;
        private static final int PCM_BUFFER_BYTES = 16 * 1024;
        private static final int BYTES_PER_FRAME = CHANNEL_COUNT * 2;

        private final String ffmpegPath;
        private final String streamUrl;
        private final byte[] pcmBytes = new byte[PCM_BUFFER_BYTES];
        private final short[] pcmSamples = new short[PCM_BUFFER_BYTES / 2];
        private volatile Process process;
        private volatile InputStream output;
        private volatile ByteArrayOutputStream diagnostics;
        private volatile Thread diagnosticsReader;
        private volatile Process checkedProcess;
        private volatile boolean seekRequested;
        private volatile boolean closed;

        private FfmpegPcmStream(String ffmpegPath, String streamUrl) {
            this.ffmpegPath = ffmpegPath;
            this.streamUrl = streamUrl;
        }

        private synchronized void startAt(long seekMs) throws IOException {
            if (this.closed) throw new IOException("SoundCloud FFmpeg stream is already closed");
            List<String> command = new ArrayList<>();
            command.add(this.ffmpegPath);
            command.addAll(List.of(
                "-hide_banner", "-loglevel", "error", "-nostdin",
                "-user_agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36",
                "-headers", "Origin: https://soundcloud.com\r\nReferer: https://soundcloud.com/\r\n",
                "-i", this.streamUrl
            ));
            if (seekMs > 0) {
                // Output-side seeking works with SoundCloud HLS playlists that cannot seek by input timestamp.
                command.add("-ss");
                command.add(String.format(Locale.ROOT, "%.3f", seekMs / 1000.0));
            }
            command.addAll(List.of(
                "-vn", "-map", "0:a:0",
                "-ac", Integer.toString(CHANNEL_COUNT),
                "-ar", Integer.toString(SAMPLE_RATE),
                "-c:a", "pcm_s16le",
                "-f", "s16le", "pipe:1"
            ));

            Process started;
            try {
                started = new ProcessBuilder(command).start();
            } catch (IOException exception) {
                throw new IOException("Could not start FFmpeg for SoundCloud HLS. Install ffmpeg or set plugins.pulselink.soundcloud.ffmpegPath.", exception);
            }

            this.process = started;
            this.output = started.getInputStream();
            this.checkedProcess = null;
            this.seekRequested = false;
            this.diagnostics = new ByteArrayOutputStream();
            ByteArrayOutputStream processDiagnostics = this.diagnostics;
            this.diagnosticsReader = new Thread(
                () -> readDiagnostics(started, processDiagnostics),
                "pulselink-soundcloud-ffmpeg-stderr"
            );
            this.diagnosticsReader.setDaemon(true);
            this.diagnosticsReader.start();
        }

        private void provideFrames(AudioPipeline pipeline) throws Exception {
            InputStream currentOutput = this.output;
            if (currentOutput == null) {
                checkForInterruption();
                return;
            }

            while (true) {
                checkForInterruption();
                int bytesRead = 0;
                boolean endOfStream = false;
                while (bytesRead < this.pcmBytes.length) {
                    checkForInterruption();
                    int count;
                    try {
                        count = currentOutput.read(this.pcmBytes, bytesRead, this.pcmBytes.length - bytesRead);
                    } catch (IOException exception) {
                        if (this.seekRequested || this.closed) {
                            throw new InterruptedException("SoundCloud FFmpeg stream interrupted");
                        }
                        throw exception;
                    }
                    if (count == -1) {
                        checkForInterruption();
                        checkExitStatus(this.process);
                        endOfStream = true;
                        break;
                    }
                    if (count > 0) bytesRead += count;
                }

                checkForInterruption();
                int alignedByteCount = bytesRead - (bytesRead % BYTES_PER_FRAME);
                int sampleCount = alignedByteCount / 2;
                if (sampleCount > 0) {
                    for (int i = 0; i < sampleCount; i++) {
                        int low = this.pcmBytes[i * 2] & 0xFF;
                        int high = this.pcmBytes[i * 2 + 1] << 8;
                        this.pcmSamples[i] = (short) (high | low);
                    }
                    pipeline.process(this.pcmSamples, 0, sampleCount);
                }

                if (endOfStream) return;
            }
        }

        private synchronized Process currentProcess() {
            return this.process;
        }

        private synchronized void interruptForSeek(Process expectedProcess) {
            if (expectedProcess == null || this.process != expectedProcess || this.closed) return;
            this.seekRequested = true;
            if (expectedProcess.isAlive()) expectedProcess.destroyForcibly();
        }

        private synchronized void restartAt(long seekMs) throws IOException {
            terminateProcess(true);
            startAt(seekMs);
        }

        private void checkForInterruption() throws InterruptedException {
            if (this.seekRequested || this.closed) {
                throw new InterruptedException("SoundCloud FFmpeg stream interrupted for seek or stop");
            }
        }

        private void checkExitStatus(Process completed) throws IOException {
            if (completed == null || completed == this.checkedProcess) return;
            try {
                if (!completed.waitFor(5, TimeUnit.SECONDS)) {
                    completed.destroyForcibly();
                    throw new IOException("FFmpeg did not exit after ending SoundCloud HLS output");
                }
                Thread reader = this.diagnosticsReader;
                if (reader != null) reader.join(1000);
            } catch (InterruptedException exception) {
                completed.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for FFmpeg", exception);
            }
            this.checkedProcess = completed;
            if (completed.exitValue() != 0) {
                ByteArrayOutputStream processDiagnostics = this.diagnostics;
                String error = "";
                if (processDiagnostics != null) {
                    synchronized (processDiagnostics) {
                        error = processDiagnostics.toString(StandardCharsets.UTF_8);
                    }
                }
                throw new IOException("FFmpeg exited with code " + completed.exitValue()
                    + (error.isBlank() ? "" : ": " + error.trim()));
            }
        }

        private static void readDiagnostics(Process process, ByteArrayOutputStream diagnostics) {
            try (InputStream input = process.getErrorStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    synchronized (diagnostics) {
                        int remaining = MAX_DIAGNOSTIC_BYTES - diagnostics.size();
                        if (remaining > 0) diagnostics.write(buffer, 0, Math.min(count, remaining));
                    }
                }
            } catch (IOException ignored) {
                // The playback path reports the process exit status and captured stderr.
            }
        }

        private synchronized void terminateProcess(boolean force) {
            Process current = this.process;
            InputStream currentOutput = this.output;
            this.process = null;
            this.output = null;
            if (currentOutput != null) {
                try {
                    currentOutput.close();
                } catch (IOException ignored) {
                    // The process is being stopped or restarted.
                }
            }
            if (current != null && current.isAlive()) {
                if (force) current.destroyForcibly();
                else current.destroy();
                try {
                    if (!current.waitFor(1, TimeUnit.SECONDS)) current.destroyForcibly();
                } catch (InterruptedException exception) {
                    current.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void close() {
            if (this.closed) return;
            this.closed = true;
            this.seekRequested = false;
            terminateProcess(true);
        }
    }
}
