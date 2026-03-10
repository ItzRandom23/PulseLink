package com.github.itzrandom23.pulselink.audiomack;

import com.github.itzrandom23.pulselink.ExtendedAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

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
				processDelegate(new Mp3AudioTrack(this.trackInfo, stream), executor);
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
}
