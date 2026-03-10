package com.github.itzrandom23.pulselink.audiomack;

import com.github.itzrandom23.pulselink.ExtendedAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.apache.http.client.methods.HttpHead;

import java.net.URI;

public class AudiomackAudioTrack extends ExtendedAudioTrack {

	private final AudiomackAudioSourceManager sourceManager;

	public AudiomackAudioTrack(
		AudioTrackInfo trackInfo,
		String albumName,
		String albumUrl,
		String artistUrl,
		String artistArtworkUrl,
		AudiomackAudioSourceManager sourceManager
	) {
		super(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, null, false);
		this.sourceManager = sourceManager;
	}

	@Override
	public void process(LocalAudioTrackExecutor executor) throws Exception {
		try (var httpInterface = this.sourceManager.getHttpInterface()) {
			String streamUrl = this.sourceManager.getStreamUrl(httpInterface, this.trackInfo.identifier);
			if (streamUrl == null || streamUrl.isEmpty()) {
				throw new FriendlyException(
					"Audiomack stream is not available for this track in your region.",
					FriendlyException.Severity.COMMON,
					new IllegalStateException("Missing Audiomack stream URL")
				);
			}

			try (var stream = new PersistentHttpStream(httpInterface, new URI(streamUrl), this.trackInfo.length)) {
				TrackFormat format = resolveFormat(httpInterface, streamUrl);
				if (format == TrackFormat.HLS) {
					throw new FriendlyException(
						"Audiomack returned an HLS stream which is not supported by this plugin.",
						FriendlyException.Severity.COMMON,
						new IllegalStateException("HLS stream")
					);
				}
				if (format == TrackFormat.MP3) {
					processDelegate(new Mp3AudioTrack(this.trackInfo, stream), executor);
				} else {
					processDelegate(new MpegAudioTrack(this.trackInfo, stream), executor);
				}
			}
		}
	}

	@Override
	protected AudioTrack makeShallowClone() {
		return new AudiomackAudioTrack(
			this.trackInfo,
			this.albumName,
			this.albumUrl,
			this.artistUrl,
			this.artistArtworkUrl,
			this.sourceManager
		);
	}

	@Override
	public AudioSourceManager getSourceManager() {
		return this.sourceManager;
	}

	private TrackFormat resolveFormat(com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface httpInterface, String streamUrl) {
		try {
			if (streamUrl.contains(".m3u8")) {
				return TrackFormat.HLS;
			}
			var head = new HttpHead(streamUrl);
			try (var response = httpInterface.execute(head)) {
				var typeHeader = response.getFirstHeader("Content-Type");
				if (typeHeader != null) {
					var contentType = typeHeader.getValue().toLowerCase();
					if (contentType.contains("mpegurl")) {
						return TrackFormat.HLS;
					}
					if (contentType.contains("audio/mpeg") || contentType.contains("audio/mp3")) {
						return TrackFormat.MP3;
					}
					if (contentType.contains("audio/mp4") || contentType.contains("audio/aac") || contentType.contains("audio/x-m4a")) {
						return TrackFormat.MPEG;
					}
				}
			}
		} catch (Exception ignored) {
			// Fallback to extension detection
		}

		if (streamUrl.toLowerCase().contains(".mp3")) {
			return TrackFormat.MP3;
		}
		return TrackFormat.MPEG;
	}

	private enum TrackFormat {
		MP3,
		MPEG,
		HLS
	}
}
