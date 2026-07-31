package com.github.itzrandom23.pulselink.mirror;

import com.sedmelluq.discord.lavaplayer.track.AudioItem;

import java.util.function.BiFunction;

@FunctionalInterface
public interface MirroringAudioTrackResolver
		extends BiFunction<MirroringAudioTrack, MirrorResolutionContext, AudioItem> {
}
