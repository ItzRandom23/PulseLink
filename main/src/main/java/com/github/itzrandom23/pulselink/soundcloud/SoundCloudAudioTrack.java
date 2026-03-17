package com.github.itzrandom23.pulselink.soundcloud;

import com.github.itzrandom23.pulselink.ExtendedAudioTrack;
import com.github.itzrandom23.pulselink.soundcloud.hls.SoundCloudHlsSeekableInputStream;
import com.github.itzrandom23.pulselink.soundcloud.hls.SoundCloudHlsStream;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpegts.MpegAdtsAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.net.URI;

public class SoundCloudAudioTrack extends ExtendedAudioTrack {

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
		super(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, previewUrl, isPreview);
		this.sourceManager = sourceManager;
	}

	@Override
	public void process(LocalAudioTrackExecutor executor) throws Exception {
		try (HttpInterface httpInterface = this.sourceManager.getHttpInterface()) {
			SoundCloudAudioSourceManager.StreamInfo streamInfo = this.sourceManager.getStreamInfo(httpInterface, this.trackInfo.identifier);
			if (streamInfo == null || streamInfo.streamUrl() == null || streamInfo.streamUrl().isBlank()) {
				throw new FriendlyException(
					"SoundCloud stream is not available for this track.",
					FriendlyException.Severity.COMMON,
					new IllegalStateException("Missing SoundCloud stream URL")
				);
			}

			if (streamInfo.protocol() == SoundCloudAudioSourceManager.Protocol.HLS) {
				try (SoundCloudHlsStream stream = new SoundCloudHlsStream(this.sourceManager, streamInfo.streamUrl(), 0)) {
					try (SoundCloudHlsSeekableInputStream seekable = new SoundCloudHlsSeekableInputStream(stream)) {
						String mimeType = streamInfo.mimeType().toLowerCase();
						if (mimeType.contains("mpeg")) {
							processDelegate(new MpegAudioTrack(this.trackInfo, seekable), executor);
							return;
						}
						processDelegate(new MpegAdtsAudioTrack(this.trackInfo, seekable), executor);
						return;
					}
				}
			}

			try (PersistentHttpStream stream = new PersistentHttpStream(httpInterface, new URI(streamInfo.streamUrl()), this.trackInfo.length)) {
				processDelegate(new MpegAudioTrack(this.trackInfo, stream), executor);
			}
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
}
