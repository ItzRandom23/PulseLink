package com.github.itzrandom23.pulselink.amazonmusic;

import com.github.itzrandom23.pulselink.ExtendedAudioPlaylist;
import com.github.itzrandom23.pulselink.PulseLinkTools;
import com.github.itzrandom23.pulselink.mirror.DefaultMirroringAudioTrackResolver;
import com.github.itzrandom23.pulselink.mirror.MirroringAudioSourceManager;
import com.github.itzrandom23.pulselink.mirror.MirroringAudioTrackResolver;
import com.github.topi314.lavasearch.AudioSearchManager;
import com.github.topi314.lavasearch.result.AudioSearchResult;
import com.github.topi314.lavasearch.result.BasicAudioSearchResult;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AmazonMusicSourceManager extends MirroringAudioSourceManager implements HttpConfigurable, AudioSearchManager {

	private static final Logger log = LoggerFactory.getLogger(AmazonMusicSourceManager.class);

	public static final Pattern URL_PATTERN = Pattern.compile(
		"https?://(?:www\\.)?music\\.amazon\\.[a-z.]+/(?<type>tracks|albums|artists|playlists|community-playlists|user-playlists)/(?<id>[A-Za-z0-9]+)(?:/[^?#]*)?(?:\\?.*)?",
		Pattern.CASE_INSENSITIVE
	);
	public static final String SEARCH_PREFIX = "amzsearch:";
	public static final Set<AudioSearchResult.Type> SEARCH_TYPES = Set.of(AudioSearchResult.Type.TRACK);
	private static final String API_BASE = "http://us2.leonodes.xyz:15482";
	private static final int DEFAULT_SEARCH_LIMIT = 10;
	private static final long FAILURE_CACHE_TTL_MS = 30_000L;
	private static final int FAILURE_CACHE_CLEANUP_THRESHOLD = 256;

	private int searchLimit = DEFAULT_SEARCH_LIMIT;
	private final Map<String, Long> recentFailures = new ConcurrentHashMap<>();

	public AmazonMusicSourceManager(String[] providers, AudioPlayerManager audioPlayerManager) {
		this(providers, unused -> audioPlayerManager);
	}

	public AmazonMusicSourceManager(String[] providers, Function<Void, AudioPlayerManager> audioPlayerManager) {
		this(audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers));
	}

	public AmazonMusicSourceManager(Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver resolver) {
		super(audioPlayerManager, resolver);
		this.httpInterfaceManager.configureRequests(config -> RequestConfig.copy(config)
			.setConnectTimeout(10000)
			.setConnectionRequestTimeout(10000)
			.setSocketTimeout(15000)
			.build());
	}

	public void setSearchLimit(int searchLimit) {
		this.searchLimit = searchLimit;
	}

	@NotNull
	@Override
	public String getSourceName() {
		return "amazonmusic";
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
		var extended = super.decodeTrack(input);
		return new AmazonMusicAudioTrack(
			trackInfo,
			extended.albumName,
			extended.albumUrl,
			extended.artistUrl,
			extended.artistArtworkUrl,
			extended.previewUrl,
			extended.isPreview,
			this
		);
	}

	@Override
	@Nullable
	public AudioSearchResult loadSearch(@NotNull String query, @NotNull Set<AudioSearchResult.Type> types) {
		if (!query.startsWith(SEARCH_PREFIX)) {
			return null;
		}
		if (types.isEmpty()) {
			types = SEARCH_TYPES;
		}
		if (!types.contains(AudioSearchResult.Type.TRACK)) {
			return null;
		}

		try {
			var tracks = searchTracks(query.substring(SEARCH_PREFIX.length()).trim());
			if (tracks.isEmpty()) {
				return null;
			}
			return new BasicAudioSearchResult(
				tracks,
				new ArrayList<>(),
				new ArrayList<>(),
				new ArrayList<>(),
				new ArrayList<>()
			);
		} catch (Exception e) {
			log.error("Amazon Music search failed", e);
			return null;
		}
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
		String identifier = reference.identifier;

		if (identifier.startsWith(SEARCH_PREFIX)) {
			try {
				var query = identifier.substring(SEARCH_PREFIX.length()).trim();
				var tracks = searchTracks(query);
				if (tracks.isEmpty()) {
					return AudioReference.NO_TRACK;
				}
				return tracks.get(0);
			} catch (Exception e) {
				log.warn("Amazon Music search failed for {}", identifier, e);
				return AudioReference.NO_TRACK;
			}
		}

		var matcher = URL_PATTERN.matcher(identifier);
		if (!matcher.matches()) {
			return null;
		}

		if (isRecentlyFailed(identifier)) {
			return AudioReference.NO_TRACK;
		}

		try {
			String type = matcher.group("type").toLowerCase();
			AudioItem resolved;
			switch (type) {
				case "tracks":
					resolved = resolveSong(identifier);
					break;
				case "albums":
					resolved = resolveAlbum(identifier);
					break;
				case "artists":
					resolved = resolveArtist(identifier);
					break;
				case "playlists":
					resolved = resolvePlaylist(identifier);
					break;
				case "community-playlists":
					resolved = resolveCommunityPlaylist(identifier);
					break;
				case "user-playlists":
					resolved = resolveUserPlaylist(identifier);
					break;
				default:
					resolved = AudioReference.NO_TRACK;
					break;
			}

			if (resolved == null || resolved == AudioReference.NO_TRACK) {
				markFailed(identifier);
				return AudioReference.NO_TRACK;
			}

			return resolved;
		} catch (Exception e) {
			log.warn("Failed to load Amazon Music item {}", identifier, e);
			markFailed(identifier);
			return AudioReference.NO_TRACK;
		}
	}

	private ArrayList<AudioTrack> searchTracks(String query) throws IOException {
		if (query == null || query.isBlank()) {
			return new ArrayList<>();
		}
		String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
		var request = new HttpGet(API_BASE + "/api/search/songs?query=" + encoded + "&page=1&limit=" + searchLimit);
		var json = PulseLinkTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
		var items = json != null ? json.get("data") : null;
		var tracks = new ArrayList<AudioTrack>();
		if (items == null || items.isNull() || items.values().isEmpty()) {
			return tracks;
		}

		for (var item : items.values()) {
			var track = parseTrack(item);
			if (track != null) {
				tracks.add(track);
			}
		}
		return tracks;
	}

	private AudioItem resolveSong(String url) throws IOException {
		var data = getDataJson("/api/songs?url=", url);
		if (data == null) {
			return AudioReference.NO_TRACK;
		}
		var track = parseTrack(data);
		return track != null ? track : AudioReference.NO_TRACK;
	}

	private AudioItem resolveAlbum(String url) throws IOException {
		var data = getDataJson("/api/albums?url=", url, true);
		return parseCollection(data, "Amazon Music Album", ExtendedAudioPlaylist.Type.ALBUM, url, null);
	}

	private AudioItem resolveArtist(String url) throws IOException {
		var data = getDataJson("/api/artists?url=", url, true);
		String artistName = data != null ? data.get("name").safeText() : null;
		String displayName = (artistName == null || artistName.isBlank()) ? "Artist Top Songs" : artistName + " - Top Songs";
		return parseCollection(data, displayName, ExtendedAudioPlaylist.Type.ARTIST, url, displayName);
	}

	private AudioItem resolvePlaylist(String url) throws IOException {
		var data = getDataJson("/api/playlists?url=", url, true);
		return parseCollection(data, "Amazon Music Playlist", ExtendedAudioPlaylist.Type.PLAYLIST, url, null);
	}

	private AudioItem resolveCommunityPlaylist(String url) throws IOException {
		var data = getDataJson("/api/community-playlists?url=", url, true);
		return parseCollection(data, "Amazon Music Community Playlist", ExtendedAudioPlaylist.Type.PLAYLIST, url, null);
	}

	private AudioItem resolveUserPlaylist(String url) throws IOException {
		var data = getDataJson("/api/community-playlists?url=", url, true);
		return parseCollection(data, "Amazon Music User Playlist", ExtendedAudioPlaylist.Type.PLAYLIST, url, null);
	}

	private boolean isRecentlyFailed(String identifier) {
		Long last = recentFailures.get(identifier);
		if (last == null) {
			return false;
		}

		long now = System.currentTimeMillis();
		if ((now - last) >= FAILURE_CACHE_TTL_MS) {
			recentFailures.remove(identifier, last);
			return false;
		}

		return true;
	}

	private void markFailed(String identifier) {
		long now = System.currentTimeMillis();
		recentFailures.put(identifier, now);
		if (recentFailures.size() >= FAILURE_CACHE_CLEANUP_THRESHOLD) {
			recentFailures.entrySet().removeIf(entry -> (now - entry.getValue()) >= FAILURE_CACHE_TTL_MS);
		}
	}

	private JsonBrowser getDataJson(String pathPrefix, String url) throws IOException {
		return getDataJson(pathPrefix, url, false);
	}

	private JsonBrowser getDataJson(String pathPrefix, String url, boolean appendIsrcFalse) throws IOException {
		String encoded = URLEncoder.encode(url, StandardCharsets.UTF_8);
		String finalUrl = API_BASE + pathPrefix + encoded;
		if (appendIsrcFalse) {
			finalUrl = finalUrl + "&isrc=false";
		}
		var request = new HttpGet(finalUrl);
		var json = PulseLinkTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
		if (json == null || json.isNull()) {
			return null;
		}
		return json.get("data");
	}

	private AudioItem parseCollection(JsonBrowser data, String fallbackName, ExtendedAudioPlaylist.Type type, String fallbackUrl, String overrideName) {
		if (data == null || data.isNull()) {
			return AudioReference.NO_TRACK;
		}

		var tracksNode = data.get("songs");
		if (tracksNode == null || tracksNode.isNull() || tracksNode.values().isEmpty()) {
			tracksNode = data.get("topSongs");
		}
		if (tracksNode == null || tracksNode.isNull() || tracksNode.values().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		var tracks = new ArrayList<AudioTrack>();
		for (var item : tracksNode.values()) {
			var track = parseTrack(item);
			if (track != null) {
				tracks.add(track);
			}
		}
		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		String name = overrideName != null ? overrideName : data.get("name").safeText();
		if (name == null || name.isBlank()) {
			name = fallbackName;
		}

		String url = data.get("url").safeText();
		if (url == null || url.isBlank()) {
			url = fallbackUrl;
		}

		String artworkUrl = data.get("image").safeText();
		String author = null;
		var artistNode = data.get("artist");
		if (artistNode != null && !artistNode.isNull()) {
			author = artistNode.get("name").safeText();
		}

		Integer totalTracks = null;
		long totalSongs = data.get("totalSongs").asLong(0);
		if (totalSongs > 0) {
			totalTracks = (int) totalSongs;
		}

		return new AmazonMusicAudioPlaylist(name, tracks, type, url, artworkUrl, author, totalTracks);
	}

	private AmazonMusicAudioTrack parseTrack(JsonBrowser item) {
		if (item == null || item.isNull()) {
			return null;
		}

		String id = item.get("id").text();
		String title = item.get("title").text();
		if (title == null || title.isBlank()) {
			title = item.get("name").text();
		}
		if (id == null || title == null) {
			return null;
		}

		String url = item.get("url").safeText();
		if (url == null || url.isBlank()) {
			url = "https://music.amazon.com/tracks/" + id;
		}

		String artworkUrl = item.get("image").safeText();
		long duration = item.get("duration").asLong(0);
		String isrc = item.get("isrc").safeText();
		String artistName = parseArtistName(item);
		String artistUrl = parseArtistUrl(item);

		var info = new AudioTrackInfo(
			title,
			artistName,
			duration,
			id,
			false,
			url,
			artworkUrl,
			isrc
		);

		return new AmazonMusicAudioTrack(
			info,
			null,
			null,
			artistUrl,
			null,
			null,
			false,
			this
		);
	}

	private String parseArtistName(JsonBrowser item) {
		var artist = item.get("artist");
		if (artist != null && !artist.isNull()) {
			var name = artist.get("name").safeText();
			if (name != null && !name.isBlank()) {
				return name;
			}
			var raw = artist.safeText();
			if (raw != null && !raw.isBlank()) {
				return raw;
			}
		}

		var artists = item.get("artists");
		if (artists != null && artists.isList()) {
			var names = artists.values().stream()
				.map(v -> v.get("name").safeText() != null ? v.get("name").safeText() : v.safeText())
				.filter(v -> v != null && !v.isBlank())
				.collect(Collectors.toList());
			if (!names.isEmpty()) {
				return String.join(", ", names);
			}
		}

		String artistName = item.get("artistName").safeText();
		if (artistName != null && !artistName.isBlank()) {
			return artistName;
		}

		return "Unknown";
	}

	private String parseArtistUrl(JsonBrowser item) {
		var artist = item.get("artist");
		if (artist != null && !artist.isNull()) {
			var url = artist.get("url").safeText();
			if (url != null && !url.isBlank()) {
				return url;
			}
		}
		return null;
	}

	@Override
	public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
		this.httpInterfaceManager.configureRequests(configurator);
	}

	@Override
	public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
		this.httpInterfaceManager.configureBuilder(configurator);
	}
}
