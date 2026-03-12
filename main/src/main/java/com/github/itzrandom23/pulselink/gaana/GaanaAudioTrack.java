package com.github.itzrandom23.pulselink.gaana;

import com.github.itzrandom23.pulselink.ExtendedAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpegts.MpegAdtsAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class GaanaAudioTrack extends ExtendedAudioTrack {

	private final GaanaAudioSourceManager sourceManager;

	public GaanaAudioTrack(
		AudioTrackInfo trackInfo,
		String albumName,
		String albumUrl,
		String artistUrl,
		String artistArtworkUrl,
		String previewUrl,
		boolean isPreview,
		GaanaAudioSourceManager sourceManager
	) {
		super(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, previewUrl, isPreview);
		this.sourceManager = sourceManager;
	}

	@Override
	public void process(LocalAudioTrackExecutor executor) throws Exception {
		try (HttpInterface httpInterface = this.sourceManager.getHttpInterface()) {
			GaanaAudioSourceManager.GaanaStreamInfo streamInfo = this.sourceManager.getStreamInfo(httpInterface, this.trackInfo.identifier);
			if (streamInfo == null || streamInfo.streamUrl() == null || streamInfo.streamUrl().isBlank()) {
				throw new FriendlyException(
					"Gaana stream is not available for this track.",
					FriendlyException.Severity.COMMON,
					new IllegalStateException("Missing Gaana stream URL")
				);
			}

			if (streamInfo.protocol() == GaanaAudioSourceManager.Protocol.HLS) {
				// Prefer resolving segments from the HLS manifest on this host/IP.
				// API-provided signed segment URLs may be bound to a different requester and return 403.
				List<String> segments = this.sourceManager.resolveHlsSegments(httpInterface, streamInfo.streamUrl());
				if (segments.isEmpty()) {
					segments = new ArrayList<>(streamInfo.segments());
				}
				if (segments.isEmpty()) {
					throw new FriendlyException(
						"Gaana HLS stream returned no playable segments.",
						FriendlyException.Severity.COMMON,
						new IllegalStateException("No HLS segments")
					);
				}
				try (SegmentedStream stream = new SegmentedStream(this.sourceManager, segments)) {
					processDelegate(new MpegAdtsAudioTrack(this.trackInfo, stream), executor);
					return;
				}
			}

			try (PersistentHttpStream stream = new PersistentHttpStream(httpInterface, new URI(streamInfo.streamUrl()), this.trackInfo.length)) {
				processDelegate(new MpegAudioTrack(this.trackInfo, stream), executor);
			}
		}
	}

	@Override
	protected AudioTrack makeShallowClone() {
		return new GaanaAudioTrack(
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
		private static final int MAX_PARALLEL_FETCHES = 3;
		private static final int PREFETCH_WINDOW = 6;

		private final GaanaAudioSourceManager sourceManager;
		private final List<String> segments;
		private final ExecutorService fetchExecutor;
		private final Map<Integer, Future<byte[]>> pendingSegments;

		private int nextScheduleIndex;
		private int nextReadIndex;
		private byte[] currentSegmentData;
		private int currentOffset;
		private boolean closed;

		private SegmentedStream(GaanaAudioSourceManager sourceManager, List<String> segments) {
			this.sourceManager = sourceManager;
			this.segments = segments;
			this.fetchExecutor = Executors.newFixedThreadPool(MAX_PARALLEL_FETCHES, runnable -> {
				Thread thread = new Thread(runnable, "pulselink-gaana-prefetch");
				thread.setDaemon(true);
				return thread;
			});
			this.pendingSegments = new HashMap<>();
			scheduleAhead();
		}

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			int read = read(one, 0, 1);
			return read == -1 ? -1 : one[0] & 0xFF;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			while (true) {
				if (this.currentSegmentData == null || this.currentOffset >= this.currentSegmentData.length) {
					if (!openNextSegment()) {
						return -1;
					}
				}

				int remaining = this.currentSegmentData.length - this.currentOffset;
				int toCopy = Math.min(len, remaining);
				System.arraycopy(this.currentSegmentData, this.currentOffset, b, off, toCopy);
				this.currentOffset += toCopy;
				if (this.currentOffset >= this.currentSegmentData.length) {
					closeCurrent();
				}
				return toCopy;
			}
		}

		private boolean openNextSegment() throws IOException {
			closeCurrent();
			if (this.nextReadIndex >= this.segments.size()) {
				return false;
			}

			scheduleAhead();
			Future<byte[]> future = this.pendingSegments.remove(this.nextReadIndex);
			if (future == null) {
				throw new IOException("Gaana segment was not scheduled: " + this.nextReadIndex);
			}

			try {
				this.currentSegmentData = future.get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for Gaana segment", e);
			} catch (ExecutionException e) {
				Throwable cause = e.getCause();
				if (cause instanceof IOException ioException) {
					throw ioException;
				}
				throw new IOException("Failed to fetch Gaana segment", cause);
			}
			this.currentOffset = 0;
			this.nextReadIndex++;
			scheduleAhead();
			return this.currentSegmentData != null && this.currentSegmentData.length > 0;
		}

		private void scheduleAhead() {
			while (!this.closed
				&& this.nextScheduleIndex < this.segments.size()
				&& this.pendingSegments.size() < PREFETCH_WINDOW) {
				final int segmentIndex = this.nextScheduleIndex++;
				final String segmentUrl = this.segments.get(segmentIndex).trim();
				if (!(segmentUrl.startsWith("http://") || segmentUrl.startsWith("https://"))) {
					continue;
				}
				this.pendingSegments.put(segmentIndex, this.fetchExecutor.submit(() -> downloadSegment(segmentUrl)));
			}
		}

		private byte[] downloadSegment(String segmentUrl) throws IOException {
			IOException lastError = null;
			for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
				try (HttpInterface httpInterface = this.sourceManager.getHttpInterface()) {
					try (CloseableHttpResponse response = httpInterface.execute(this.sourceManager.createRequest(segmentUrl))) {
						int status = response.getStatusLine().getStatusCode();
						if (status != 200) {
							throw new IOException("Failed to fetch Gaana segment, status " + status);
						}

						try (InputStream input = response.getEntity().getContent()) {
							ByteArrayOutputStream output = new ByteArrayOutputStream(131072);
							IOUtils.copy(input, output);
							byte[] data = output.toByteArray();
							if (data.length == 0) {
								throw new IOException("Gaana segment returned no data");
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
						throw new IOException("Interrupted while retrying Gaana segment fetch", interrupted);
					}
				}
			}
			throw lastError != null ? lastError : new IOException("Failed to fetch Gaana segment");
		}

		private void closeCurrent() throws IOException {
			this.currentSegmentData = null;
			this.currentOffset = 0;
		}

		@Override
		public void close() throws IOException {
			this.closed = true;
			for (Future<byte[]> future : this.pendingSegments.values()) {
				future.cancel(true);
			}
			this.pendingSegments.clear();
			this.fetchExecutor.shutdownNow();
			closeCurrent();
			super.close();
		}
	}
}
