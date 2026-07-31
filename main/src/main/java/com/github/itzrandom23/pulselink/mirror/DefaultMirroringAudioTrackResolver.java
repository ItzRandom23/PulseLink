package com.github.itzrandom23.pulselink.mirror;

import com.github.itzrandom23.pulselink.applemusic.AppleMusicSourceManager;
import com.github.itzrandom23.pulselink.spotify.SpotifySourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultMirroringAudioTrackResolver implements MirroringAudioTrackResolver {

	private static final Logger log = LoggerFactory.getLogger(DefaultMirroringAudioTrackResolver.class);

	/**
	 * Global guard preventing a SoundCloud search whose result is known to have no
	 * full stream from re-entering the resolver for the identical query.  It is NOT
	 * sufficient for full cycle detection — per-resolution provider/track-level
	 * deduplication is done by {@link MirrorResolutionContext}.
	 */
	private static final Set<String> ACTIVE_SOUNDCLOUD_MIRROR_SEARCHES = ConcurrentHashMap.newKeySet();

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
	public AudioItem apply(MirroringAudioTrack track, MirrorResolutionContext ctx) {
		if (ctx.isDeadlineExceeded()) {
			log.warn("[Mirror] Resolution deadline exceeded for '{}'; aborting.", track.getInfo().title);
			return AudioReference.NO_TRACK;
		}

		for (var provider : this.providers) {
			if (ctx.isDeadlineExceeded()) {
				return AudioReference.NO_TRACK;
			}

			if (provider.startsWith(SpotifySourceManager.SEARCH_PREFIX)) {
				log.debug("[Mirror] Skipping provider '{}' (Spotify cannot be a mirror provider).", provider);
				continue;
			}
			if (provider.startsWith(AppleMusicSourceManager.SEARCH_PREFIX)) {
				log.debug("[Mirror] Skipping provider '{}' (Apple Music cannot be a mirror provider).", provider);
				continue;
			}

			String effectiveProvider = provider;

			if (provider.contains(MirroringAudioSourceManager.ISRC_PATTERN)) {
				var isrc = track.getInfo().isrc;
				if (isrc != null && !isrc.isEmpty()) {
					effectiveProvider = provider.replace(MirroringAudioSourceManager.ISRC_PATTERN,
						isrc.replace("-", ""));
				} else {
					log.debug("[Mirror] Skipping provider '{}' because this track has no ISRC.", provider);
					continue;
				}
			}
			effectiveProvider = effectiveProvider.replace(MirroringAudioSourceManager.QUERY_PATTERN,
				getTrackTitle(track));

			// ---- Cycle guard 1: per-resolution provider-key deduplication ----
			String rawProviderKey = provider + "=" + effectiveProvider;
			if (ctx.hasProviderBeenAttempted(rawProviderKey)) {
				log.debug("[Mirror] Skipping duplicate provider '{}' within resolution {}.", effectiveProvider, ctx.correlationId);
				continue;
			}
			ctx.markProviderAttempted(rawProviderKey);

			// ---- Cycle guard 2: SoundCloud global active-search guard ----
			boolean soundCloudMirrorSearch = "soundcloud".equals(track.getSourceManager().getSourceName())
				&& provider.startsWith("scsearch:");
			if (soundCloudMirrorSearch && !ACTIVE_SOUNDCLOUD_MIRROR_SEARCHES.add(effectiveProvider)) {
				log.debug("[Mirror] Skipping nested SoundCloud mirror search to prevent a preview-only result from recursing.");
				continue;
			}

			// ---- Cycle guard 3: track-identity per-resolution dedup ----
			String sourceName = track.getSourceManager() != null ? track.getSourceManager().getSourceName() : "unknown";
			String trackKey = sourceName + ":" + (track.getIdentifier() != null ? track.getIdentifier() : "");
			if (ctx.hasTrackBeenProcessed(trackKey) && ctx.mirrorDepth > 0) {
				log.warn("[Mirror] Track '{}' already processed in resolution {}; skipping.", track.getInfo().title, ctx.correlationId);
				continue;
			}
			ctx.markTrackProcessed(trackKey);

			log.debug("[Mirror] [depth {}/{}] Attempting provider '{}' for '{}' (cid:{}).",
				ctx.mirrorDepth, ctx.maxMirrorDepth, effectiveProvider,
				track.getInfo().title, ctx.correlationId);

			AudioItem item;
			try {
				item = track.loadItem(effectiveProvider);
			} catch (Exception e) {
				log.warn("[Mirror] Provider '{}' load failed for '{}' (ctx:{}): {}.",
					effectiveProvider, track.getInfo().title, ctx.correlationId, e.getMessage());
				continue;
			} finally {
				if (soundCloudMirrorSearch) ACTIVE_SOUNDCLOUD_MIRROR_SEARCHES.remove(effectiveProvider);
			}

			if (item instanceof AudioPlaylist && ((AudioPlaylist) item).getTracks().isEmpty()
				|| item == AudioReference.NO_TRACK) {
				log.debug("[Mirror] Provider '{}' produced no playable result.", effectiveProvider);
				continue;
			}

			log.debug("[Mirror] Resolved mirror track via provider '{}' (cid:{}).",
				effectiveProvider, ctx.correlationId);
			return item;
		}

		log.debug("[Mirror] No mirror providers produced a playable result for '{}'.",
			track.getInfo().title);
		return AudioReference.NO_TRACK;
	}

	public String getTrackTitle(MirroringAudioTrack track) {
		var query = track.getInfo().title;
		if (track.getInfo().author != null && !track.getInfo().author.equals("unknown")) {
			query += " " + track.getInfo().author;
		}
		return query;
	}

}