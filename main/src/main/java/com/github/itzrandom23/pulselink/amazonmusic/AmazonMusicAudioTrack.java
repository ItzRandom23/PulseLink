package com.github.itzrandom23.pulselink.amazonmusic;

import com.github.itzrandom23.pulselink.mirror.MirroringAudioSourceManager;
import com.github.itzrandom23.pulselink.mirror.MirroringAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;

public class AmazonMusicAudioTrack extends MirroringAudioTrack {

	public AmazonMusicAudioTrack(AudioTrackInfo trackInfo, AmazonMusicSourceManager sourceManager) {
		this(trackInfo, null, null, null, null, null, false, sourceManager);
	}

	public AmazonMusicAudioTrack(AudioTrackInfo trackInfo, String albumName, String albumUrl, String artistUrl, String artistArtworkUrl, String previewUrl, boolean isPreview, MirroringAudioSourceManager sourceManager) {
		super(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, previewUrl, isPreview, sourceManager);
	}

	@Override
	protected InternalAudioTrack createAudioTrack(AudioTrackInfo trackInfo, SeekableInputStream stream) {
		return new Mp3AudioTrack(trackInfo, stream);
	}

	@Override
	protected AudioTrack makeShallowClone() {
		return new AmazonMusicAudioTrack(
			this.trackInfo,
			this.albumName,
			this.albumUrl,
			this.artistUrl,
			this.artistArtworkUrl,
			this.previewUrl,
			this.isPreview,
			(AmazonMusicSourceManager) this.sourceManager
		);
	}
}
