package com.github.itzrandom23.pulselink.vkmusic;

import com.github.itzrandom23.pulselink.ExtendedAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.List;

public class VkMusicAudioPlaylist extends ExtendedAudioPlaylist {

	public VkMusicAudioPlaylist(String name, List<AudioTrack> tracks, Type type, String identifier, String artworkURL, String author, Integer totalTracks) {
		super(name, tracks, type, identifier, artworkURL, author, totalTracks);
	}

}
