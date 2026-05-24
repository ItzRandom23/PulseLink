package com.github.itzrandom23.pulselink.soundcloud;

import com.github.itzrandom23.pulselink.ExtendedAudioTrack;
import com.github.itzrandom23.pulselink.mirror.MirroringAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpegts.MpegAdtsAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;

public class SoundCloudAudioTrack extends MirroringAudioTrack {

	private final SoundCloudAudioSourceManager sourceManager;

	public SoundCloudAudioTrack(
		AudioTrackInfo trackInfo,
		String albumName,
		String albumUrl,
		String artistUrl,
		String artistArtworkUrl,
		String previewUrl,
		boolean isPreview,
		SoundCloudAudioSourceManager sourceManager
	) {
		super(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, previewUrl, isPreview, sourceManager);
		this.sourceManager = sourceManager;
	}

	@Override
	protected InternalAudioTrack createAudioTrack(AudioTrackInfo trackInfo, SeekableInputStream inputStream) {
		return new Mp3AudioTrack(trackInfo, inputStream);
	}

	@Override
	public void process(LocalAudioTrackExecutor executor) throws Exception {
		try (HttpInterface httpInterface = this.sourceManager.getHttpInterface()) {
			SoundCloudAudioSourceManager.SoundCloudStreamInfo streamInfo = this.sourceManager.getStreamInfo(httpInterface, this.trackInfo.identifier);
			if (streamInfo == null || streamInfo.streamUrl() == null || streamInfo.streamUrl().isBlank()) {
				processMirror(executor, "Missing SoundCloud stream URL");
				return;
			}

			if (streamInfo.hls()) {
				List<String> parts = this.sourceManager.resolveHlsParts(httpInterface, streamInfo.streamUrl());
				if (parts.isEmpty()) {
					processMirror(executor, "SoundCloud HLS stream returned no playable segments");
					return;
				}
				try (var stream = new SegmentedStream(this.sourceManager, parts)) {
					processDelegate(new MpegAdtsAudioTrack(this.trackInfo, stream), executor);
				}
				return;
			}

			try (var stream = new PersistentHttpStream(httpInterface, new URI(streamInfo.streamUrl()), this.trackInfo.length)) {
				if ("MP3".equalsIgnoreCase(streamInfo.codec()) || (streamInfo.mimeType() != null && streamInfo.mimeType().toLowerCase().contains("mpeg"))) {
					processDelegate(new Mp3AudioTrack(this.trackInfo, stream), executor);
				} else {
					processDelegate(new MpegAudioTrack(this.trackInfo, stream), executor);
				}
			}
		}
	}

	private void processMirror(LocalAudioTrackExecutor executor, String reason) throws Exception {
		try {
			super.process(executor);
		} catch (Exception exception) {
			throw new FriendlyException(
				"SoundCloud stream is not available and no mirror provider could play this track.",
				FriendlyException.Severity.COMMON,
				new IllegalStateException(reason, exception)
			);
		}
	}

	@Override
	protected AudioTrack makeShallowClone() {
		return new SoundCloudAudioTrack(
			this.trackInfo,
			this.albumName,
			this.albumUrl,
			this.artistUrl,
			this.artistArtworkUrl,
			this.previewUrl,
			this.isPreview,
			this.sourceManager
		);
	}

	@Override
	public AudioSourceManager getSourceManager() {
		return this.sourceManager;
	}

	private static final class SegmentedStream extends InputStream {
		private static final int MAX_RETRIES = 3;

		private final SoundCloudAudioSourceManager sourceManager;
		private final List<String> parts;
		private int nextPartIndex;
		private byte[] currentPart;
		private int currentOffset;

		private SegmentedStream(SoundCloudAudioSourceManager sourceManager, List<String> parts) {
			this.sourceManager = sourceManager;
			this.parts = parts;
		}

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			int read = read(one, 0, 1);
			return read == -1 ? -1 : one[0] & 0xFF;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			while (this.currentPart == null || this.currentOffset >= this.currentPart.length) {
				if (!openNextPart()) {
					return -1;
				}
			}

			int remaining = this.currentPart.length - this.currentOffset;
			int toCopy = Math.min(len, remaining);
			System.arraycopy(this.currentPart, this.currentOffset, b, off, toCopy);
			this.currentOffset += toCopy;
			return toCopy;
		}

		private boolean openNextPart() throws IOException {
			if (this.nextPartIndex >= this.parts.size()) {
				return false;
			}
			String url = this.parts.get(this.nextPartIndex++);
			this.currentPart = downloadPart(url);
			this.currentOffset = 0;
			return this.currentPart.length > 0;
		}

		private byte[] downloadPart(String url) throws IOException {
			IOException lastError = null;
			for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
				try (HttpInterface httpInterface = this.sourceManager.getHttpInterface()) {
					try (CloseableHttpResponse response = httpInterface.execute(this.sourceManager.createRequest(url))) {
						int status = response.getStatusLine().getStatusCode();
						if (status != 200) {
							throw new IOException("Failed to fetch SoundCloud segment, status " + status);
						}
						try (InputStream input = response.getEntity().getContent()) {
							ByteArrayOutputStream output = new ByteArrayOutputStream(131072);
							IOUtils.copy(input, output);
							byte[] data = output.toByteArray();
							if (data.length == 0) {
								throw new IOException("SoundCloud segment returned no data");
							}
							return data;
						}
					}
				} catch (IOException e) {
					lastError = e;
					if (attempt == MAX_RETRIES) {
						break;
					}
					try {
						Thread.sleep(250L * attempt);
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						throw new IOException("Interrupted while retrying SoundCloud segment fetch", interrupted);
					}
				}
			}
			throw lastError != null ? lastError : new IOException("Failed to fetch SoundCloud segment");
		}
	}
}
