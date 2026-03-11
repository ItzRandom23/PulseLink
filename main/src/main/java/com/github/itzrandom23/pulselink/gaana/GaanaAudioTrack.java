package com.github.itzrandom23.pulselink.gaana;

import com.github.itzrandom23.pulselink.mirror.MirroringAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;

public class GaanaAudioTrack extends MirroringAudioTrack {

	public GaanaAudioTrack(
		AudioTrackInfo trackInfo,
		String albumName,
		String albumUrl,
		String artistUrl,
		String artistArtworkUrl,
		GaanaAudioSourceManager sourceManager
	) {
		super(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, null, false, sourceManager);
	}

	@Override
	protected InternalAudioTrack createAudioTrack(AudioTrackInfo trackInfo, SeekableInputStream inputStream) {
		return new MpegAudioTrack(trackInfo, inputStream);
	}

	@Override
	protected AudioTrack makeShallowClone() {
		return new GaanaAudioTrack(
			this.trackInfo,
			this.albumName,
			this.albumUrl,
			this.artistUrl,
			this.artistArtworkUrl,
			(GaanaAudioSourceManager) this.sourceManager
		);
	}
}
