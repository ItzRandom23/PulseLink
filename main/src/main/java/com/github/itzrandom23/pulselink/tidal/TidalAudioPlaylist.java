package com.github.itzrandom23.pulselink.tidal;

import com.github.itzrandom23.pulselink.ExtendedAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.List;

public class TidalAudioPlaylist extends ExtendedAudioPlaylist {
	public TidalAudioPlaylist(String name, List<AudioTrack> tracks, ExtendedAudioPlaylist.Type type, String url, String artworkURL, String author, Integer totalTracks) {
		super(name, tracks, type, url, artworkURL, author, totalTracks);
	}
}
