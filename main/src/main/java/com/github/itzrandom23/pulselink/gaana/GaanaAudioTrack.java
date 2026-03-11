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
import org.apache.http.client.methods.CloseableHttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

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
				try (SegmentedStream stream = new SegmentedStream(httpInterface, this.sourceManager, segments)) {
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
		private final HttpInterface httpInterface;
		private final GaanaAudioSourceManager sourceManager;
		private final List<String> segments;

		private int index;
		private CloseableHttpResponse response;
		private InputStream currentStream;

		private SegmentedStream(HttpInterface httpInterface, GaanaAudioSourceManager sourceManager, List<String> segments) {
			this.httpInterface = httpInterface;
			this.sourceManager = sourceManager;
			this.segments = segments;
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
				if (this.currentStream == null) {
					if (!openNextSegment()) {
						return -1;
					}
				}

				int read = this.currentStream.read(b, off, len);
				if (read >= 0) {
					return read;
				}
				closeCurrent();
			}
		}

		private boolean openNextSegment() throws IOException {
			closeCurrent();
			if (this.index >= this.segments.size()) {
				return false;
			}

			String segmentUrl = this.segments.get(this.index++).trim();
			if (!(segmentUrl.startsWith("http://") || segmentUrl.startsWith("https://"))) {
				throw new IOException("Invalid Gaana segment URL: " + segmentUrl);
			}
			this.response = this.httpInterface.execute(this.sourceManager.createRequest(segmentUrl));
			int status = this.response.getStatusLine().getStatusCode();
			if (status != 200) {
				closeCurrent();
				throw new IOException("Failed to fetch Gaana segment, status " + status);
			}
			this.currentStream = this.response.getEntity().getContent();
			return true;
		}

		private void closeCurrent() throws IOException {
			if (this.currentStream != null) {
				this.currentStream.close();
				this.currentStream = null;
			}
			if (this.response != null) {
				this.response.close();
				this.response = null;
			}
		}

		@Override
		public void close() throws IOException {
			closeCurrent();
			super.close();
		}
	}
}
