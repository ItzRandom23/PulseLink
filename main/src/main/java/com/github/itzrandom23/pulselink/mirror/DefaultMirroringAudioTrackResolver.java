package com.github.itzrandom23.pulselink.mirror;

import com.github.itzrandom23.pulselink.applemusic.AppleMusicSourceManager;
import com.github.itzrandom23.pulselink.spotify.SpotifySourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultMirroringAudioTrackResolver implements MirroringAudioTrackResolver {

	private static final Logger log = LoggerFactory.getLogger(DefaultMirroringAudioTrackResolver.class);

	private String[] providers = {
		"ytsearch:\"" + MirroringAudioSourceManager.ISRC_PATTERN + "\"",
		"ytsearch:" + MirroringAudioSourceManager.QUERY_PATTERN
	};

	public DefaultMirroringAudioTrackResolver(String[] providers) {
		if (providers != null && providers.length > 0) {
			this.providers = providers;
		}
	}

	@Override
	public AudioItem apply(MirroringAudioTrack mirroringAudioTrack) {
		for (var provider : providers) {
			if (provider.startsWith(SpotifySourceManager.SEARCH_PREFIX)) {
				log.warn("Skipping provider \"{}\" because spotify search can not be used as a mirror provider.", provider);
				continue;
			}

			if (provider.startsWith(AppleMusicSourceManager.SEARCH_PREFIX)) {
				log.warn("Skipping provider \"{}\" because Apple Music search can not be used as a mirror provider.", provider);
				continue;
			}

			if (provider.contains(MirroringAudioSourceManager.ISRC_PATTERN)) {
				if (mirroringAudioTrack.getInfo().isrc != null && !mirroringAudioTrack.getInfo().isrc.isEmpty()) {
					provider = provider.replace(MirroringAudioSourceManager.ISRC_PATTERN, mirroringAudioTrack.getInfo().isrc.replace("-", ""));
				} else {
					log.debug("Skipping provider \"{}\" because this track does not have an ISRC.", provider);
					continue;
				}
			}

			provider = provider.replace(MirroringAudioSourceManager.QUERY_PATTERN, getTrackTitle(mirroringAudioTrack));
			log.debug("Attempting mirror resolution with provider \"{}\" for track \"{}\".", provider, mirroringAudioTrack.getInfo().title);

			AudioItem item;
			try {
				item = mirroringAudioTrack.loadItem(provider);
			} catch (Exception e) {
				log.error("Failed to load track from provider \"{}\"!", provider, e);
				continue;
			}
			// If the track is an empty playlist, skip the provider
			if (item instanceof AudioPlaylist && ((AudioPlaylist) item).getTracks().isEmpty() || item == AudioReference.NO_TRACK) {
				log.debug("Provider \"{}\" did not produce a playable mirror result.", provider);
				continue;
			}
			log.debug("Resolved mirror track via provider \"{}\".", provider);
			return item;
		}

		log.debug("No mirror providers produced a playable result for track \"{}\".", mirroringAudioTrack.getInfo().title);
		return AudioReference.NO_TRACK;
	}

	public String getTrackTitle(MirroringAudioTrack mirroringAudioTrack) {
		var query = mirroringAudioTrack.getInfo().title;
		if (!mirroringAudioTrack.getInfo().author.equals("unknown")) {
			query += " " + mirroringAudioTrack.getInfo().author;
		}
		return query;
	}

}
