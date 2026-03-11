package com.github.itzrandom23.pulselink.gaana;

import com.github.itzrandom23.pulselink.ExtendedAudioPlaylist;
import com.github.itzrandom23.pulselink.ExtendedAudioSourceManager;
import com.github.itzrandom23.pulselink.PulseLinkTools;
import com.github.topi314.lavasearch.AudioSearchManager;
import com.github.topi314.lavasearch.result.AudioSearchResult;
import com.github.topi314.lavasearch.result.BasicAudioSearchResult;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GaanaAudioSourceManager extends ExtendedAudioSourceManager implements HttpConfigurable, AudioSearchManager {

	private static final Logger log = LoggerFactory.getLogger(GaanaAudioSourceManager.class);

	private static final String DEFAULT_API_BASE = "https://gaanapi-wine.vercel.app/api";
	private static final String SEARCH_PREFIX = "gnsearch:";
	private static final String STREAM_QUALITY = "high";
	private static final Pattern URL_PATTERN = Pattern.compile("^@?(?:https?://)?(?:www\\.)?gaana\\.com/(?<type>song|album|playlist|artist)/(?<seokey>[\\w-]+)(?:[?#].*)?$", Pattern.CASE_INSENSITIVE);
	private static final Set<AudioSearchResult.Type> SEARCH_TYPES = Set.of(AudioSearchResult.Type.TRACK);
	private static final int SEARCH_LIMIT = 10;
	private static final int ARTIST_TRACK_LIMIT = 25;
	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";

	private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();
	private final String apiBase;

	public GaanaAudioSourceManager() {
		this(DEFAULT_API_BASE);
	}

	public GaanaAudioSourceManager(String apiBase) {
		if (apiBase == null || apiBase.isBlank()) {
			this.apiBase = DEFAULT_API_BASE;
			return;
		}

		String normalized = apiBase.trim();
		if (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		if (!normalized.endsWith("/api")) {
			normalized = normalized + "/api";
		}
		this.apiBase = normalized;
	}

	@NotNull
	@Override
	public String getSourceName() {
		return "gaana";
	}

	@Override
	public @Nullable AudioSearchResult loadSearch(@NotNull String query, @NotNull Set<AudioSearchResult.Type> types) {
		if (types.isEmpty()) {
			types = SEARCH_TYPES;
		}
		if (!types.contains(AudioSearchResult.Type.TRACK) || !query.startsWith(SEARCH_PREFIX)) {
			return null;
		}

		try {
			var item = getSearch(query.substring(SEARCH_PREFIX.length()));
			if (item instanceof BasicAudioPlaylist playlist) {
				return new BasicAudioSearchResult(playlist.getTracks(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to search Gaana", e);
		}

		return new BasicAudioSearchResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
		try {
			String identifier = reference.identifier;
			if (identifier.startsWith(SEARCH_PREFIX)) {
				return getSearch(identifier.substring(SEARCH_PREFIX.length()));
			}

			Matcher matcher = URL_PATTERN.matcher(identifier);
			if (!matcher.find()) {
				return null;
			}

			String type = matcher.group("type").toLowerCase(Locale.ROOT);
			String seokey = matcher.group("seokey");

			return switch (type) {
				case "song" -> {
					AudioTrack track = getSong(seokey);
					yield track != null ? track : AudioReference.NO_TRACK;
				}
				case "album" -> getAlbum(seokey);
				case "playlist" -> getPlaylist(seokey);
				case "artist" -> getArtist(seokey);
				default -> null;
			};
		} catch (IOException e) {
			throw new RuntimeException("Failed to load Gaana item", e);
		}
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
		var extended = super.decodeTrack(input);
		return new GaanaAudioTrack(
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

	public HttpInterface getHttpInterface() {
		return this.httpInterfaceManager.getInterface();
	}

	HttpGet createRequest(String url) {
		HttpGet request = new HttpGet(url);
		request.setHeader("User-Agent", USER_AGENT);
		request.setHeader("Accept", "*/*");
		request.setHeader("Origin", "https://gaana.com");
		request.setHeader("Referer", "https://gaana.com/");
		return request;
	}

	GaanaStreamInfo getStreamInfo(HttpInterface httpInterface, String identifier) throws IOException {
		String trackId = resolveTrackId(httpInterface, identifier);
		if (trackId == null) {
			return null;
		}

		JsonBrowser json = getJson(httpInterface, this.apiBase + "/stream/" + encode(trackId) + "?quality=" + encode(STREAM_QUALITY));
		JsonBrowser data = extractApiData(json);
		if (data == null || data.isNull()) {
			return null;
		}

		String hlsUrl = getFirstText(data, "hlsUrl", "hls_url");
		String directUrl = getFirstText(data, "url", "stream_url");
		List<String> segments = parseSegments(data.get("segments"));

		if (hlsUrl != null) {
			return new GaanaStreamInfo(trackId, hlsUrl, Protocol.HLS, segments);
		}
		if (directUrl != null) {
			return new GaanaStreamInfo(trackId, directUrl, Protocol.HTTPS, segments);
		}
		if (!segments.isEmpty()) {
			return new GaanaStreamInfo(trackId, segments.get(0), Protocol.HLS, segments);
		}
		return null;
	}

	List<String> resolveHlsSegments(HttpInterface httpInterface, String masterUrl) throws IOException {
		String masterBody = fetchText(httpInterface, masterUrl);
		String mediaUrl = firstMediaUrl(masterBody, masterUrl);
		String mediaBody = fetchText(httpInterface, mediaUrl);
		List<String> segments = new ArrayList<>();
		for (String line : mediaBody.split("\n")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			segments.add(resolveUrl(mediaUrl, trimmed));
		}
		return segments;
	}

	private AudioItem getSearch(String query) throws IOException {
		String encodedQuery = encode(query);
		JsonBrowser json = getJson(this.apiBase + "/search/songs?q=" + encodedQuery + "&limit=" + SEARCH_LIMIT);
		JsonBrowser data = extractApiData(json);
		List<AudioTrack> tracks = parseTrackList(extractList(data));
		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}
		return new BasicAudioPlaylist("Gaana Search: " + query, tracks, null, true);
	}

	private AudioTrack getSong(String seokey) throws IOException {
		JsonBrowser json = getJson(this.apiBase + "/songs?seokey=" + encode(seokey));
		JsonBrowser data = extractApiData(json);
		return parseTrack(data);
	}

	private AudioItem getAlbum(String seokey) throws IOException {
		JsonBrowser json = getJson(this.apiBase + "/albums?seokey=" + encode(seokey));
		JsonBrowser data = extractApiData(json);
		if (data == null || data.isNull()) {
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = parseTrackList(data.get("tracks"));
		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new ExtendedAudioPlaylist(
			getFirstText(data, "title", "name"),
			tracks,
			ExtendedAudioPlaylist.Type.ALBUM,
			"https://gaana.com/album/" + seokey,
			getFirstText(data, "artworkUrl", "artwork"),
			getFirstTrackAuthor(tracks),
			tracks.size()
		);
	}

	private AudioItem getPlaylist(String seokey) throws IOException {
		JsonBrowser json = getJson(this.apiBase + "/playlists?seokey=" + encode(seokey));
		JsonBrowser data = extractApiData(json);
		JsonBrowser playlist = data != null && !data.get("playlist").isNull() ? data.get("playlist") : data;
		if (playlist == null || playlist.isNull()) {
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = parseTrackList(playlist.get("tracks"));
		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new ExtendedAudioPlaylist(
			getFirstText(playlist, "title", "name"),
			tracks,
			ExtendedAudioPlaylist.Type.PLAYLIST,
			"https://gaana.com/playlist/" + seokey,
			getFirstText(playlist, "artworkUrl", "artwork"),
			getFirstText(playlist, "author"),
			tracks.size()
		);
	}

	private AudioItem getArtist(String seokey) throws IOException {
		JsonBrowser json = getJson(this.apiBase + "/artists?seokey=" + encode(seokey));
		JsonBrowser data = extractApiData(json);
		if (data == null || data.isNull()) {
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = parseTrackList(data.get("top_tracks"));
		if (tracks.size() > ARTIST_TRACK_LIMIT) {
			tracks = new ArrayList<>(tracks.subList(0, ARTIST_TRACK_LIMIT));
		}
		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new ExtendedAudioPlaylist(
			getFirstText(data, "name", "title"),
			tracks,
			ExtendedAudioPlaylist.Type.ARTIST,
			getFirstText(data, "artist_url"),
			getFirstText(data, "artwork"),
			getFirstText(data, "name"),
			tracks.size()
		);
	}

	private String resolveTrackId(HttpInterface httpInterface, String identifier) throws IOException {
		if (identifier != null && identifier.matches("^\\d+$")) {
			return identifier;
		}
		JsonBrowser songJson = getJson(httpInterface, this.apiBase + "/songs?seokey=" + encode(identifier));
		JsonBrowser song = extractApiData(songJson);
		return getFirstText(song, "track_id", "id");
	}

	private List<String> parseSegments(JsonBrowser segmentsNode) {
		if (segmentsNode == null || segmentsNode.isNull()) {
			return Collections.emptyList();
		}
		List<String> segments = new ArrayList<>();
		for (JsonBrowser segment : segmentsNode.values()) {
			String value = getFirstText(segment, "url");
			if (value == null) {
				value = getText(segment);
			}
			if (value != null) {
				value = value.trim();
			}
			// Ignore object-json text like {"url":"...","durationMs":...}; only keep actual URLs.
			if (value != null && !value.isBlank() && (value.startsWith("http://") || value.startsWith("https://"))) {
				segments.add(value);
			}
		}
		return segments;
	}

	private JsonBrowser extractApiData(JsonBrowser json) {
		if (json == null || json.isNull()) {
			return null;
		}
		JsonBrowser success = json.get("success");
		if (!success.isNull() && !success.asBoolean(true)) {
			return null;
		}
		if (!json.get("data").isNull()) {
			return json.get("data");
		}
		return json;
	}

	private JsonBrowser getJson(String url) throws IOException {
		try (HttpInterface httpInterface = this.httpInterfaceManager.getInterface()) {
			return getJson(httpInterface, url);
		}
	}

	private JsonBrowser getJson(HttpInterface httpInterface, String url) throws IOException {
		return PulseLinkTools.fetchResponseAsJson(httpInterface, createRequest(url));
	}

	private List<AudioTrack> parseTrackList(JsonBrowser listNode) {
		if (listNode == null || listNode.isNull()) {
			return Collections.emptyList();
		}

		List<AudioTrack> tracks = new ArrayList<>();
		for (JsonBrowser item : listNode.values()) {
			AudioTrack track = parseTrack(item);
			if (track != null) {
				tracks.add(track);
			}
		}
		return tracks;
	}

	private AudioTrack parseTrack(JsonBrowser track) {
		if (track == null || track.isNull()) {
			return null;
		}

		String title = getFirstText(track, "title", "name");
		String identifier = getFirstText(track, "track_id", "id", "seokey");
		long duration = track.get("duration").asLong(0L) * 1000L;

		if (title == null || identifier == null || duration <= 0L) {
			return null;
		}

		String author = getArtists(track);
		String songUrl = getFirstText(track, "song_url", "url");
		String artworkUrl = getFirstText(track, "artworkUrl", "artwork", "artwork_url");
		String albumName = getFirstText(track, "album");
		String albumUrl = getFirstText(track, "album_url");
		String artistUrl = buildArtistUrl(track);
		String artistArtworkUrl = artworkUrl;
		String isrc = getFirstText(track, "isrc");

		AudioTrackInfo info = new AudioTrackInfo(
			title,
			author != null && !author.isEmpty() ? author : "Unknown",
			duration,
			identifier,
			false,
			songUrl != null ? songUrl : "https://gaana.com/song/" + identifier,
			artworkUrl,
			isrc
		);

		return new GaanaAudioTrack(info, albumName, albumUrl, artistUrl, artistArtworkUrl, null, false, this);
	}

	private String fetchText(HttpInterface httpInterface, String url) throws IOException {
		try (CloseableHttpResponse response = httpInterface.execute(createRequest(url))) {
			return IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
		}
	}

	private String firstMediaUrl(String playlistBody, String baseUrl) {
		for (String line : playlistBody.split("\n")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			return resolveUrl(baseUrl, trimmed);
		}
		return baseUrl;
	}

	private String resolveUrl(String base, String path) {
		try {
			return URI.create(base).resolve(path).toString();
		} catch (IllegalArgumentException ignored) {
			return path;
		}
	}

	private String buildArtistUrl(JsonBrowser track) {
		String seokeys = getFirstText(track, "artist_seokeys");
		if (seokeys != null && !seokeys.isBlank()) {
			String first = seokeys.split(",")[0].trim();
			if (!first.isEmpty()) {
				return "https://gaana.com/artist/" + first;
			}
		}
		String artistId = getFirstText(track, "artist_id");
		if (artistId != null && !artistId.isBlank()) {
			return "https://gaana.com/artist/" + artistId;
		}
		return null;
	}

	private String getArtists(JsonBrowser track) {
		JsonBrowser artistsNode = track.get("artists");
		if (!artistsNode.isNull()) {
			List<String> names = new ArrayList<>();
			for (JsonBrowser artist : artistsNode.values()) {
				String name = getFirstText(artist, "name");
				if (name == null) {
					name = getText(artist);
				}
				if (name != null && !name.isBlank()) {
					names.add(name.trim());
				}
			}
			if (!names.isEmpty()) {
				return String.join(", ", names);
			}
			String text = getText(artistsNode);
			if (text != null) {
				return text;
			}
		}
		return getFirstText(track, "artist");
	}

	private String getFirstTrackAuthor(List<AudioTrack> tracks) {
		if (tracks.isEmpty()) {
			return null;
		}
		return tracks.get(0).getInfo().author;
	}

	private JsonBrowser extractList(JsonBrowser node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.get("results").isNull()) {
			return node.get("results");
		}
		return node;
	}

	private String getFirstText(JsonBrowser node, String... fields) {
		for (String field : fields) {
			String value = getText(node, field);
			if (value != null && !value.isEmpty()) {
				return value;
			}
		}
		return null;
	}

	private String getText(JsonBrowser node, String field) {
		if (node == null || node.isNull()) {
			return null;
		}
		return getText(node.get(field));
	}

	private String getText(JsonBrowser node) {
		if (node == null || node.isNull()) {
			return null;
		}
		String text = node.text();
		return text != null && !text.isBlank() ? text : null;
	}

	private String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	@Override
	public void shutdown() {
		try {
			this.httpInterfaceManager.close();
		} catch (IOException e) {
			log.error("Failed to close Gaana HTTP interface manager", e);
		}
	}

	@Override
	public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
		this.httpInterfaceManager.configureRequests(configurator);
	}

	@Override
	public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
		this.httpInterfaceManager.configureBuilder(configurator);
	}

	enum Protocol {
		HLS,
		HTTPS
	}

	static final class GaanaStreamInfo {
		private final String trackId;
		private final String streamUrl;
		private final Protocol protocol;
		private final List<String> segments;

		GaanaStreamInfo(String trackId, String streamUrl, Protocol protocol, List<String> segments) {
			this.trackId = trackId;
			this.streamUrl = streamUrl;
			this.protocol = protocol;
			this.segments = segments;
		}

		String trackId() {
			return this.trackId;
		}

		String streamUrl() {
			return this.streamUrl;
		}

		Protocol protocol() {
			return this.protocol;
		}

		List<String> segments() {
			return this.segments;
		}
	}
}
