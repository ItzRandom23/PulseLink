package com.github.itzrandom23.pulselink.shazam;

import com.github.itzrandom23.pulselink.mirror.DefaultMirroringAudioTrackResolver;
import com.github.itzrandom23.pulselink.mirror.MirroringAudioSourceManager;
import com.github.itzrandom23.pulselink.mirror.MirroringAudioTrackResolver;
import com.github.topi314.lavasearch.AudioSearchManager;
import com.github.topi314.lavasearch.result.AudioSearchResult;
import com.github.topi314.lavasearch.result.BasicAudioSearchResult;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.DataInput;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class ShazamAudioSourceManager extends MirroringAudioSourceManager implements HttpConfigurable, AudioSearchManager {

	private static final Logger log = LoggerFactory.getLogger(ShazamAudioSourceManager.class);
	private static final String SEARCH_PREFIX = "szsearch:";
	private static final String ALT_SEARCH_PREFIX = "shsearch:";
	private static final String SEARCH_API_BASE = "https://www.shazam.com/services/amapi/v1/catalog/US/search?types=songs&term=%s&limit=%d";
	private static final int SEARCH_LIMIT = 1;
	private static final int MAX_PAGE_CACHE_ENTRIES = 128;
	private static final Map<String, CachedPage> PAGE_CACHE = new ConcurrentHashMap<>();

	private final ShazamNextDataParser nextDataParser = new ShazamNextDataParser();

	public ShazamAudioSourceManager(String[] providers, AudioPlayerManager audioPlayerManager) {
		this(providers, unused -> audioPlayerManager);
	}

	public ShazamAudioSourceManager(String[] providers, Function<Void, AudioPlayerManager> audioPlayerManager) {
		this(audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers));
	}

	public ShazamAudioSourceManager(Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver resolver) {
		super(audioPlayerManager, resolver);
		this.httpInterfaceManager.configureRequests(config -> RequestConfig.copy(config)
			.setConnectTimeout(10000)
			.setConnectionRequestTimeout(10000)
			.setSocketTimeout(15000)
			.build());
	}

	@NotNull
	@Override
	public String getSourceName() {
		return "shazam";
	}

	@Override
	public @Nullable AudioSearchResult loadSearch(@NotNull String query, @NotNull Set<AudioSearchResult.Type> types) {
		if (!types.isEmpty() && !types.contains(AudioSearchResult.Type.TRACK) || !isSearchQuery(query)) {
			return null;
		}
		try {
			AudioItem item = getSearch(stripSearchPrefix(query));
			if (item instanceof AudioTrack track) {
				return new BasicAudioSearchResult(List.of(track), List.of(), List.of(), List.of(), List.of());
			}
			if (item instanceof AudioPlaylist playlist) {
				return new BasicAudioSearchResult(playlist.getTracks(), List.of(), List.of(), List.of(), List.of());
			}
		} catch (IOException e) {
			log.warn("Failed to search Shazam for query {}", stripSearchPrefix(query), e);
		}
		return new BasicAudioSearchResult(List.of(), List.of(), List.of(), List.of(), List.of());
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
		if (isSearchQuery(reference.identifier)) {
			try {
				return getSearch(stripSearchPrefix(reference.identifier));
			} catch (IOException e) {
				log.warn("Failed to search Shazam for query {}", stripSearchPrefix(reference.identifier), e);
				return AudioReference.NO_TRACK;
			}
		}

		ShazamUrl page = ShazamUrl.parse(reference.identifier);
		if (page == null || page.route == ShazamUrl.Route.UNSUPPORTED) {
			return null;
		}
		try {
			return loadPage(page);
		} catch (FriendlyException | IOException e) {
			log.warn("Failed to load Shazam {} URL {}", page.route.name().toLowerCase(Locale.ROOT), page.originalUrl, e);
			return AudioReference.NO_TRACK;
		}
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
		throw new IOException("Previously serialized Shazam tracks are no longer supported; load the Shazam URL again.");
	}

	private AudioItem loadPage(ShazamUrl page) throws IOException {
		PageSongs pageSongs = pageSongs(page);
		// Artist pages are already reduced to Shazam's ranked top-track cards.
		// Do not discard collaborations just because their artist credit differs.
		List<ShazamNextDataParser.Song> selectedSongs = pageSongs.page.route == ShazamUrl.Route.ARTIST
			? pageSongs.songs : ShazamPageSelector.select(pageSongs.page, pageSongs.songs);
		if (pageSongs.page.route == ShazamUrl.Route.TRACK) {
			return selectedSongs.isEmpty() ? AudioReference.NO_TRACK : loadAppleMusicUrl(selectedSongs.get(0).url);
		}
		String playlistName = switch (pageSongs.page.route) {
			case ARTIST -> pageSongs.page.slug == null ? "Shazam Artist - Top Tracks" : displayName(pageSongs.page.slug) + " - Top Tracks";
			case ALBUM -> !pageSongs.albumName.isBlank() ? pageSongs.albumName : selectedSongs.isEmpty() ? "Shazam Album" : selectedSongs.get(0).album;
			case CHART -> "Shazam " + pageSongs.page.id + " - " + pageSongs.page.slug;
			case RADIOSPINS -> "Shazam Top 200 on Radio";
			case TRACK, UNSUPPORTED -> "Shazam";
		};
		return playlist(playlistName, selectedSongs);
	}

	private PageSongs pageSongs(ShazamUrl page) throws IOException {
		long now = System.currentTimeMillis();
		CachedPage cached = PAGE_CACHE.get(page.normalizedUrl);
		if (cached != null && cached.expiresAt > now) {
			return new PageSongs(cached.page, cached.songs, cached.albumName);
		}
		ShazamHtmlClient.Page response = new ShazamHtmlClient(this.httpInterfaceManager.getInterface()).get(page);
		ShazamUrl resolvedPage = ShazamUrl.parse(response.url());
		if (resolvedPage == null || resolvedPage.route == ShazamUrl.Route.UNSUPPORTED) resolvedPage = page;
		ShazamNextDataParser.Song track = resolvedPage.route == ShazamUrl.Route.TRACK ? nextDataParser.parseTrack(response.html()) : null;
		List<ShazamNextDataParser.Song> songs = switch (resolvedPage.route) {
			case CHART -> nextDataParser.parseAppleMusicLinks(response.html());
			case RADIOSPINS -> nextDataParser.parseRadioSpins(response.html());
			case ARTIST -> nextDataParser.parseArtistTopSongs(response.html());
			default -> track == null ? nextDataParser.parse(response.html()) : List.of(track);
		};
		String albumName = resolvedPage.route == ShazamUrl.Route.ALBUM ? nextDataParser.parseAlbumName(response.html()) : "";
		if (songs.isEmpty()) {
			throw new FriendlyException("Shazam page did not contain Next.js song metadata.", FriendlyException.Severity.SUSPICIOUS, null);
		}
		if (PAGE_CACHE.size() >= MAX_PAGE_CACHE_ENTRIES) {
			PAGE_CACHE.clear();
		}
		PAGE_CACHE.put(page.normalizedUrl, new CachedPage(resolvedPage, songs, albumName, now + cacheDurationMillis(resolvedPage.route)));
		return new PageSongs(resolvedPage, songs, albumName);
	}


	private AudioItem playlist(String name, List<ShazamNextDataParser.Song> songs) {
		Map<String, AudioTrack> tracks = new LinkedHashMap<>();
		for (ShazamNextDataParser.Song song : songs) {
			AudioItem item = loadAppleMusicUrl(song.url);
			if (item instanceof AudioTrack track) {
				tracks.putIfAbsent(track.getInfo().identifier, track);
			} else if (item instanceof AudioPlaylist playlist) {
				for (AudioTrack track : playlist.getTracks()) tracks.putIfAbsent(track.getInfo().identifier, track);
			}
		}
		return tracks.isEmpty() ? AudioReference.NO_TRACK : new BasicAudioPlaylist(name, new ArrayList<>(tracks.values()), null, false);
	}

	/** Routes a Shazam-discovered URL through the already registered Apple Music source. */
	private AudioItem loadAppleMusicUrl(String url) {
		if (url == null || url.isBlank()) return AudioReference.NO_TRACK;
		CompletableFuture<AudioItem> result = new CompletableFuture<>();
		getAudioPlayerManager().loadItem(url, new AudioLoadResultHandler() {
			@Override public void trackLoaded(AudioTrack track) { result.complete(track); }
			@Override public void playlistLoaded(AudioPlaylist playlist) { result.complete(playlist); }
			@Override public void noMatches() { result.complete(AudioReference.NO_TRACK); }
			@Override public void loadFailed(FriendlyException exception) { result.completeExceptionally(exception); }
		});
		return result.join();
	}

	private AudioItem getSearch(String query) throws IOException {
		String url = String.format(SEARCH_API_BASE, URLEncoder.encode(query, StandardCharsets.UTF_8), SEARCH_LIMIT);
		HttpGet request = new HttpGet(url);
		request.setHeader("Accept", "application/json");
		request.setHeader("Accept-Language", "en-US,en;q=0.9");
		request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36");
		try (var response = this.httpInterfaceManager.getInterface().execute(request)) {
			if (response.getStatusLine().getStatusCode() != 200 || response.getEntity() == null) return AudioReference.NO_TRACK;
			JsonBrowser songs = JsonBrowser.parse(IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8)).get("results").get("songs").get("data");
			for (JsonBrowser item : songs.values()) {
				JsonBrowser attributes = item.get("attributes");
				String appleMusicUrl = attributes.get("url").safeText();
				if (!appleMusicUrl.isBlank()) return loadAppleMusicUrl(appleMusicUrl);
			}
			return AudioReference.NO_TRACK;
		}
	}

	private boolean isSearchQuery(String query) {
		return query.startsWith(SEARCH_PREFIX) || query.startsWith(ALT_SEARCH_PREFIX);
	}

	private String stripSearchPrefix(String query) {
		return query.substring(query.startsWith(SEARCH_PREFIX) ? SEARCH_PREFIX.length() : ALT_SEARCH_PREFIX.length());
	}

	private long cacheDurationMillis(ShazamUrl.Route route) {
		return switch (route) {
			case TRACK, ALBUM -> 30L * 60L * 1000L;
			case ARTIST -> 15L * 60L * 1000L;
			case CHART, RADIOSPINS -> 3L * 60L * 1000L;
			case UNSUPPORTED -> 0L;
		};
	}

	private String displayName(String slug) {
		StringBuilder output = new StringBuilder();
		for (String word : slug.replace('_', '-').split("-")) {
			if (word.isBlank()) continue;
			if (!output.isEmpty()) output.append(' ');
			output.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return output.isEmpty() ? "Shazam Artist" : output.toString();
	}

	private static class CachedPage {
		private final ShazamUrl page;
		private final List<ShazamNextDataParser.Song> songs;
		private final String albumName;
		private final long expiresAt;
		private CachedPage(ShazamUrl page, List<ShazamNextDataParser.Song> songs, String albumName, long expiresAt) { this.page = page; this.songs = List.copyOf(songs); this.albumName = albumName; this.expiresAt = expiresAt; }
	}

	private static class PageSongs {
		private final ShazamUrl page;
		private final List<ShazamNextDataParser.Song> songs;
		private final String albumName;
		private PageSongs(ShazamUrl page, List<ShazamNextDataParser.Song> songs, String albumName) { this.page = page; this.songs = songs; this.albumName = albumName; }
	}
}
