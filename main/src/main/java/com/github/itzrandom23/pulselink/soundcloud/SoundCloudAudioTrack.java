package com.github.itzrandom23.pulselink.soundcloud;

import com.github.itzrandom23.pulselink.mirror.MirroringAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.io.IOException;
import java.net.URI;

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
				processWithFfmpegOrMirror(executor, streamInfo.streamUrl());
				return;
			}

			String codec = streamInfo.codec();
			String mimeType = streamInfo.mimeType();
			boolean isMp3 = "MP3".equalsIgnoreCase(codec)
				|| ((codec == null || codec.isBlank()) && mimeType != null
					&& mimeType.toLowerCase(java.util.Locale.ROOT).contains("mpeg"));
			if (!isMp3) {
				processWithFfmpegOrMirror(executor, streamInfo.streamUrl());
				return;
			}

			try (var stream = new PersistentHttpStream(httpInterface, new URI(streamInfo.streamUrl()), this.trackInfo.length)) {
				processDelegate(new Mp3AudioTrack(this.trackInfo, stream), executor);
			}
		}
	}

	private void processWithFfmpegOrMirror(LocalAudioTrackExecutor executor, String streamUrl) throws Exception {
		try {
			processDelegate(new FfmpegPcmAudioTrack(
				this.trackInfo,
				this.sourceManager.getFfmpegPath(),
				streamUrl
			), executor);
		} catch (Exception exception) {
			if (!isUnsupportedSampleAes(exception)) {
				throw exception;
			}
			processMirror(executor, "SoundCloud returned SAMPLE-AES encrypted fMP4, which FFmpeg cannot decode");
		}
	}

	private boolean isUnsupportedSampleAes(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			String message = cause.getMessage();
			if (message != null && message.contains("SAMPLE-AES encryption is not supported yet")) {
				return true;
			}
		}
		return false;
	}

	private void processMirror(LocalAudioTrackExecutor executor, String reason) throws Exception {
		if (this.resolutionContext == null) {
			this.resolutionContext = new com.github.itzrandom23.pulselink.mirror.MirrorResolutionContext(
				this.sourceManager.getSourceName(),
				this.getIdentifier()
			);
		}
		var ctx = this.resolutionContext;
		if (ctx.isDeadlineExceeded()) {
			throw new FriendlyException("SoundCloud mirror resolution deadline exceeded.",
				FriendlyException.Severity.COMMON, new java.util.concurrent.TimeoutException(reason));
		}
		if (!ctx.pushMirror(this.sourceManager.getSourceName(), this.getIdentifier())) {
			throw new FriendlyException(
				"SoundCloud mirror depth exceeded (tried " + ctx.mirrorDepth + " mirrors for " + ctx.originalProvider + " track).",
				FriendlyException.Severity.COMMON,
				new IllegalStateException(reason)
			);
		}
		try {
			super.processWithContext(executor, ctx);
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

}
