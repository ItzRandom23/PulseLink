package com.github.itzrandom23.pulselink.soundcloud.hls;

import com.github.itzrandom23.pulselink.soundcloud.SoundCloudAudioSourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.atomic.AtomicReference;

public class SoundCloudHlsStream extends InputStream {

	private static final int PIPE_BUFFER = 1024 * 1024 * 2;

	private final PipedInputStream input;
	private final PipedOutputStream output;
	private final AtomicReference<IOException> error = new AtomicReference<>();
	private final SoundCloudHlsHandler handler;
	private final Thread thread;
	private volatile boolean closed;

	public SoundCloudHlsStream(SoundCloudAudioSourceManager sourceManager, String url, double startTimeSeconds) throws IOException {
		this.input = new PipedInputStream(PIPE_BUFFER);
		this.output = new PipedOutputStream(this.input);
		this.handler = new SoundCloudHlsHandler(sourceManager, this.output, url, startTimeSeconds);
		this.thread = new Thread(() -> {
			try {
				handler.run();
			} catch (Exception e) {
				if (e instanceof IOException io) {
					error.compareAndSet(null, io);
				} else {
					error.compareAndSet(null, new IOException("SoundCloud HLS handler failed", e));
				}
			}
		}, "pulselink-soundcloud-hls");
		this.thread.setDaemon(true);
		this.thread.start();
	}

	@Override
	public int read() throws IOException {
		checkError();
		int value = input.read();
		if (value == -1) {
			checkError();
		}
		return value;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		checkError();
		int read = input.read(b, off, len);
		if (read == -1) {
			checkError();
		}
		return read;
	}

	private void checkError() throws IOException {
		IOException ex = error.get();
		if (ex != null) {
			throw ex;
		}
	}

	@Override
	public void close() throws IOException {
		if (closed) {
			return;
		}
		closed = true;
		handler.stop();
		try {
			output.close();
		} catch (IOException ignored) {
		}
		input.close();
	}
}
