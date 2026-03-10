package com.github.itzrandom23.pulselink.jiosaavn;

import com.github.itzrandom23.pulselink.ExtendedAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.List;

public class JioSaavnAudioPlaylist extends ExtendedAudioPlaylist {
	public JioSaavnAudioPlaylist(
		String name, List<AudioTrack> tracks, ExtendedAudioPlaylist.Type type, String identifier, String artworkURL, String author, Integer totalTracks
	) {
		super(name, tracks, type, identifier, artworkURL, author, totalTracks);
	}
}
