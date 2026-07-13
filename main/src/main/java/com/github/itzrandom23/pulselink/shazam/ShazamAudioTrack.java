package com.github.itzrandom23.pulselink.shazam;

import com.github.itzrandom23.pulselink.mirror.MirroringAudioSourceManager;
import com.github.itzrandom23.pulselink.mirror.MirroringAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;

/** Metadata belongs to Shazam; playback is resolved through the configured mirror providers. */
final class ShazamAudioTrack extends MirroringAudioTrack {

	ShazamAudioTrack(AudioTrackInfo trackInfo, MirroringAudioSourceManager sourceManager) {
		super(trackInfo, null, null, null, null, null, false, sourceManager);
	}

	@Override
	protected InternalAudioTrack createAudioTrack(AudioTrackInfo trackInfo, SeekableInputStream stream) {
		return new MpegAudioTrack(trackInfo, stream);
	}

	@Override
	protected AudioTrack makeShallowClone() {
		return new ShazamAudioTrack(this.trackInfo, this.sourceManager);
	}
}
