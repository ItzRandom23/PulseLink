package com.github.itzrandom23.pulselink.shazam;

import com.github.itzrandom23.pulselink.mirror.DefaultMirroringAudioTrackResolver;
import com.github.itzrandom23.pulselink.mirror.MirroringAudioSourceManager;
import com.github.itzrandom23.pulselink.mirror.MirroringAudioTrackResolver;
import com.github.topi314.lavasearch.AudioSearchManager;
import com.github.topi314.lavasearch.result.AudioSearchResult;
import com.github.topi314.lavasearch.result.BasicAudioSearchResult;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class ShazamAudioSourceManager extends MirroringAudioSourceManager implements HttpConfigurable, AudioSearchManager {

	private static final Logger log = LoggerFactory.getLogger(ShazamAudioSourceManager.class);
	private static final String SEARCH_PREFIX = "szsearch:";
	private static final String ALT_SEARCH_PREFIX = "shsearch:";
	private static final String SEARCH_API_BASE = "https://www.shazam.com/services/amapi/v1/catalog/US/search?types=songs&term=%s&limit=%d";
	private static final int SEARCH_LIMIT = 10;
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
			if (item instanceof BasicAudioPlaylist playlist) {
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
		var extended = super.decodeTrack(input);
		return new ShazamAudioTrack(trackInfo, extended.albumName, extended.albumUrl, extended.artistUrl, extended.artistArtworkUrl, this);
	}

	private AudioItem loadPage(ShazamUrl page) throws IOException {
		List<ShazamNextDataParser.Song> songs = pageSongs(page);
		List<ShazamNextDataParser.Song> selectedSongs = ShazamPageSelector.select(page, songs);
		if (page.route == ShazamUrl.Route.TRACK) {
			return selectedSongs.isEmpty() ? AudioReference.NO_TRACK : toTrack(selectedSongs.get(0), page.originalUrl);
		}
		String playlistName = switch (page.route) {
			case ARTIST -> page.slug == null ? "Shazam Artist" : page.slug.replace('-', ' ').replace('_', ' ');
			case ALBUM -> selectedSongs.isEmpty() ? "Shazam Album" : selectedSongs.get(0).album;
			case CHART -> "Shazam " + page.id + " - " + page.slug;
			case TRACK, UNSUPPORTED -> "Shazam";
		};
		return playlist(playlistName, page.originalUrl, selectedSongs);
	}

	private List<ShazamNextDataParser.Song> pageSongs(ShazamUrl page) throws IOException {
		long now = System.currentTimeMillis();
		CachedPage cached = PAGE_CACHE.get(page.normalizedUrl);
		if (cached != null && cached.expiresAt > now) {
			return cached.songs;
		}
		String html = new ShazamHtmlClient(this.httpInterfaceManager.getInterface()).get(page);
		List<ShazamNextDataParser.Song> songs = nextDataParser.parse(html);
		if (songs.isEmpty()) {
			throw new FriendlyException("Shazam page did not contain Next.js song metadata.", FriendlyException.Severity.SUSPICIOUS, null);
		}
		if (PAGE_CACHE.size() >= MAX_PAGE_CACHE_ENTRIES) {
			PAGE_CACHE.clear();
		}
		PAGE_CACHE.put(page.normalizedUrl, new CachedPage(songs, now + cacheDurationMillis(page.route)));
		return songs;
	}


	private AudioItem playlist(String name, String originalUrl, List<ShazamNextDataParser.Song> songs) {
		Map<String, AudioTrack> tracks = new LinkedHashMap<>();
		for (ShazamNextDataParser.Song song : songs) {
			AudioTrack track = toTrack(song, originalUrl);
			tracks.putIfAbsent(track.getInfo().identifier, track);
		}
		return tracks.isEmpty() ? AudioReference.NO_TRACK : new BasicAudioPlaylist(name, new ArrayList<>(tracks.values()), null, false);
	}

	private AudioTrack toTrack(ShazamNextDataParser.Song song, String originalUrl) {
		String identifier = !song.shazamTrackId.isBlank() ? song.shazamTrackId : !song.appleMusicTrackId.isBlank() ? song.appleMusicTrackId
			: !song.isrc.isBlank() ? song.isrc : Integer.toUnsignedString((song.artist + "\u0000" + song.title).hashCode());
		AudioTrackInfo info = new AudioTrackInfo(song.title, song.artist, song.duration, identifier, false, originalUrl,
			song.artworkUrl.isBlank() ? null : song.artworkUrl, song.isrc.isBlank() ? null : song.isrc);
		return new ShazamAudioTrack(info, song.album.isBlank() ? null : song.album, null, null, null, this);
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
			List<AudioTrack> tracks = new ArrayList<>();
			for (JsonBrowser item : songs.values()) {
				JsonBrowser attributes = item.get("attributes");
				String title = attributes.get("name").safeText();
				String artist = attributes.get("artistName").safeText();
				if (!title.isBlank() && !artist.isBlank()) {
					String artwork = attributes.get("artwork").get("url").safeText().replace("{w}", "1000").replace("{h}", "1000");
					tracks.add(new ShazamAudioTrack(new AudioTrackInfo(title, artist, attributes.get("durationInMillis").asLong(0L), item.get("id").safeText(), false,
						attributes.get("url").safeText(), artwork.isBlank() ? null : artwork, attributes.get("isrc").safeText()), null, null, null, null, this));
				}
			}
			return tracks.isEmpty() ? AudioReference.NO_TRACK : new BasicAudioPlaylist("Shazam Search: " + query, tracks, null, true);
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
			case CHART -> 3L * 60L * 1000L;
			case UNSUPPORTED -> 0L;
		};
	}

	private static class CachedPage {
		private final List<ShazamNextDataParser.Song> songs;
		private final long expiresAt;
		private CachedPage(List<ShazamNextDataParser.Song> songs, long expiresAt) { this.songs = List.copyOf(songs); this.expiresAt = expiresAt; }
	}
}
