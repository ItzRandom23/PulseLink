package com.github.itzrandom23.pulselink.audiomack;

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
import com.sedmelluq.discord.lavaplayer.track.*;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInput;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AudiomackAudioSourceManager extends ExtendedAudioSourceManager implements HttpConfigurable, AudioSearchManager {

	private static final Logger log = LoggerFactory.getLogger(AudiomackAudioSourceManager.class);

	private static final String API_BASE = "https://api.audiomack.com/v1";
	private static final String CONSUMER_KEY = "audiomack-web";
	private static final String CONSUMER_SECRET = "bd8a07e9f23fbe9d808646b730f89b8e";

	private static final String SEARCH_PREFIX = "admsearch:";
	private static final Set<AudioSearchResult.Type> SEARCH_TYPES = Set.of(AudioSearchResult.Type.TRACK);
	private static final Pattern SONG_LINK_PATTERN = Pattern.compile("href=\"(\\/[^\\\"]+\\/song\\/[^\\\"]+)\"");

	private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();
	private final SecureRandom random = new SecureRandom();
	private final AudiomackConfig config;

	public AudiomackAudioSourceManager(@NotNull AudiomackConfig config) {
		this.config = config;
	}

	@NotNull
	@Override
	public String getSourceName() {
		return "audiomack";
	}

	@Override
	public @Nullable AudioSearchResult loadSearch(@NotNull String query, @NotNull Set<AudioSearchResult.Type> types) {
		if (types.isEmpty()) {
			types = SEARCH_TYPES;
		}
		if (!types.contains(AudioSearchResult.Type.TRACK)) {
			return null;
		}
		if (query.startsWith(SEARCH_PREFIX)) {
			try {
				return getSearchResult(query.substring(SEARCH_PREFIX.length()));
			} catch (IOException e) {
				throw new RuntimeException("Failed to search Audiomack", e);
			}
		}
		return null;
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
		try {
			if (reference.identifier.startsWith(SEARCH_PREFIX)) {
				return getSearch(reference.identifier.substring(SEARCH_PREFIX.length()));
			}

			if (!reference.identifier.startsWith("http")) {
				return null;
			}

			URI uri = URI.create(reference.identifier);
			if (uri.getHost() == null || !uri.getHost().contains("audiomack.com")) {
				return null;
			}

			String path = uri.getPath() == null ? "" : uri.getPath();
			if (path.contains("/song/")) {
				var song = resolveSong(uri);
				if (song == null) return AudioReference.NO_TRACK;
				var track = buildTrack(song, reference.identifier);
				return track != null ? track : AudioReference.NO_TRACK;
			}
			if (path.contains("/album/")) {
				return resolveAlbum(uri);
			}
			if (path.contains("/playlist/")) {
				return resolveArtistOrPlaylist(uri, ExtendedAudioPlaylist.Type.PLAYLIST);
			}

			return resolveArtistOrPlaylist(uri, ExtendedAudioPlaylist.Type.ARTIST);
		} catch (IOException e) {
			throw new RuntimeException("Failed to load Audiomack item", e);
		}
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
		var extended = super.decodeTrack(input);
		return new AudiomackAudioTrack(
			trackInfo,
			extended.albumName,
			extended.albumUrl,
			extended.artistUrl,
			extended.artistArtworkUrl,
			this
		);
	}

	public String getStreamUrl(HttpInterface httpInterface, String songId) throws IOException {
		var params = Map.of(
			"environment", "desktop-web",
			"section", "/search"
		);
		JsonBrowser json = makeSignedRequest(httpInterface, "GET", API_BASE + "/music/play/" + songId, params);
		JsonBrowser data = normalize(json);

		String url = getText(data, "signedUrl");
		if (url == null) url = getText(data, "signed_url");
		if (url == null) url = getText(data, "url");
		if (url == null) url = getText(data, "stream_url");
		return url;
	}

	private AudioSearchResult getSearchResult(String query) throws IOException {
		var result = getSearch(query);
		if (result instanceof BasicAudioPlaylist playlist) {
			return new BasicAudioSearchResult(playlist.getTracks(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
		}
		return new BasicAudioSearchResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
	}

	private AudioItem getSearch(String query) throws IOException {
		Map<String, String> params = new HashMap<>();
		params.put("q", query);
		params.put("limit", Integer.toString(Math.max(1, config.getSearchLimit())));
		params.put("show", "music");
		params.put("sort", "popular");
		params.put("page", "1");
		params.put("section", "/search");

		JsonBrowser json = makeSignedRequest(this.httpInterfaceManager.getInterface(), "GET", API_BASE + "/search", params);
		if (json == null || json.get("results").values().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = new ArrayList<>();
		for (var item : json.get("results").values()) {
			if (!"song".equalsIgnoreCase(getText(item, "type"))) {
				continue;
			}
			var track = resolveTrackFromPartial(item);
			if (track != null) {
				tracks.add(track);
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}
		return new BasicAudioPlaylist("Audiomack Search: " + query, tracks, null, true);
	}

	private AudioItem resolveAlbum(URI uri) throws IOException {
		var path = uri.getPath();
		if (path == null) return AudioReference.NO_TRACK;
		var parts = trimSlashes(path).split("/");
		if (parts.length < 3) return AudioReference.NO_TRACK;

		String artist = parts[0];
		String albumSlug = parts[2];

		JsonBrowser json = makeSignedRequest(this.httpInterfaceManager.getInterface(), "GET",
			API_BASE + "/music/album/" + artist + "/" + albumSlug,
			Map.of("section", path)
		);

		JsonBrowser results = json != null ? json.get("results") : null;
		if (results == null || results.isNull()) {
			return AudioReference.NO_TRACK;
		}

		JsonBrowser tracksJson = results.get("tracks");
		if (tracksJson.isNull() || tracksJson.values().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = resolveTracksFromPartials(tracksJson.values());
		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		String title = getText(results, "title");
		String artworkUrl = getFirstText(results, "image", "image_base", "image_url");
		String author = getText(results, "artist");

		return new ExtendedAudioPlaylist(
			title != null ? title : "Audiomack Album",
			tracks,
			ExtendedAudioPlaylist.Type.ALBUM,
			uri.toString(),
			artworkUrl,
			author,
			tracks.size()
		);
	}

	private AudioItem resolveArtistOrPlaylist(URI uri, ExtendedAudioPlaylist.Type type) throws IOException {
		String html = fetchText(uri.toString());
		if (html == null || html.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		Matcher matcher = SONG_LINK_PATTERN.matcher(html);
		Set<String> unique = new LinkedHashSet<>();
		while (matcher.find()) {
			unique.add(matcher.group(1));
		}

		List<AudioTrack> tracks = new ArrayList<>();
		int limit = Math.max(1, config.getArtistTrackLimit());
		for (String path : unique) {
			if (tracks.size() >= limit) {
				break;
			}
			URI songUri = URI.create("https://audiomack.com" + path);
			JsonBrowser song = resolveSong(songUri);
			AudioTrack track = buildTrack(song, songUri.toString());
			if (track != null) {
				tracks.add(track);
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		String name = deriveNameFromPath(uri.getPath(), type);
		return new ExtendedAudioPlaylist(
			name,
			tracks,
			type,
			uri.toString(),
			null,
			null,
			tracks.size()
		);
	}

	private List<AudioTrack> resolveTracksFromPartials(List<JsonBrowser> partials) throws IOException {
		List<AudioTrack> tracks = new ArrayList<>();
		for (JsonBrowser partial : partials) {
			AudioTrack track = resolveTrackFromPartial(partial);
			if (track != null) {
				tracks.add(track);
			}
		}
		return tracks;
	}

	private AudioTrack resolveTrackFromPartial(JsonBrowser partial) throws IOException {
		String uploader = resolveUploaderSlug(partial);
		String slug = getFirstText(partial, "url_slug", "slug");
		if (uploader == null || slug == null) {
			return null;
		}

		String songUrl = "https://audiomack.com/" + uploader + "/song/" + slug;
		JsonBrowser song = resolveSong(URI.create(songUrl));
		return buildTrack(song, songUrl);
	}

	private JsonBrowser resolveSong(URI uri) throws IOException {
		String path = uri.getPath();
		if (path == null) return null;
		String[] parts = trimSlashes(path).split("/");
		if (parts.length < 3) return null;

		String artist = parts[0];
		String slug = String.join("/", Arrays.copyOfRange(parts, 2, parts.length));

		return makeSignedRequest(
			this.httpInterfaceManager.getInterface(),
			"GET",
			API_BASE + "/music/song/" + artist + "/" + slug,
			Map.of("section", path)
		);
	}

	private AudioTrack buildTrack(JsonBrowser song, String songUrl) {
		JsonBrowser data = normalize(song);
		if (data == null || data.isNull()) return null;

		String id = getText(data, "id");
		if (id == null || id.isEmpty()) {
			return null;
		}

		String title = getText(data, "title");
		String artist = getText(data, "artist");
		long duration = data.get("duration").asLong(0L) * 1000L;
		String artworkUrl = getFirstText(data, "image", "image_base", "image_url");

		String albumName = getText(data, "album_title");
		String artistUrl = buildArtistUrl(data);

		AudioTrackInfo info = new AudioTrackInfo(
			title != null ? title : "Unknown title",
			artist != null ? artist : "Unknown artist",
			duration,
			id,
			false,
			songUrl,
			artworkUrl,
			null
		);
		return new AudiomackAudioTrack(info, albumName, null, artistUrl, null, this);
	}

	private String buildArtistUrl(JsonBrowser data) {
		String uploader = resolveUploaderSlug(data);
		if (uploader == null) return null;
		return "https://audiomack.com/" + uploader;
	}

	private String resolveUploaderSlug(JsonBrowser node) {
		String uploader = getText(node.get("uploader"), "url_slug");
		if (uploader == null) uploader = getText(node, "uploader_url_slug");
		if (uploader == null) uploader = getText(node, "artist_slug");
		if (uploader == null) {
			String artist = getText(node, "artist");
			if (artist != null) {
				uploader = artist.toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
			}
		}
		return uploader;
	}

	private String fetchText(String url) throws IOException {
		HttpGet request = new HttpGet(url);
		try (CloseableHttpResponse response = this.httpInterfaceManager.getInterface().execute(request)) {
			int statusCode = response.getStatusLine().getStatusCode();
			if (statusCode == HttpStatus.SC_NOT_FOUND || !HttpClientTools.isSuccessWithContent(statusCode)) {
				return null;
			}
			return IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
		}
	}

	private JsonBrowser makeSignedRequest(HttpInterface httpInterface, String method, String url, Map<String, String> params) throws IOException {
		Map<String, String> oauthParams = new HashMap<>(params);
		oauthParams.put("oauth_consumer_key", CONSUMER_KEY);
		oauthParams.put("oauth_nonce", randomHex(16));
		oauthParams.put("oauth_signature_method", "HMAC-SHA1");
		oauthParams.put("oauth_timestamp", Long.toString(Instant.now().getEpochSecond()));
		oauthParams.put("oauth_version", "1.0");

		String signature = generateSignature(method, url, oauthParams);
		String finalUrl = url + "?" + buildParamString(oauthParams) + "&oauth_signature=" + strictEncode(signature);

		HttpGet request = new HttpGet(finalUrl);
		request.setHeader("Accept", "application/json");
		return PulseLinkTools.fetchResponseAsJson(httpInterface, request);
	}

	private String generateSignature(String method, String url, Map<String, String> params) {
		String paramString = buildParamString(params);
		String base = method.toUpperCase(Locale.ROOT) + "&" + strictEncode(url) + "&" + strictEncode(paramString);
		String key = strictEncode(CONSUMER_SECRET) + "&";

		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
			return Base64.getEncoder().encodeToString(mac.doFinal(base.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException("Failed to generate Audiomack signature", e);
		}
	}

	private String buildParamString(Map<String, String> params) {
		return params.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByKey())
			.map(entry -> strictEncode(entry.getKey()) + "=" + strictEncode(entry.getValue()))
			.reduce((a, b) -> a + "&" + b)
			.orElse("");
	}

	private String strictEncode(String value) {
		String encoded = URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8);
		encoded = encoded.replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
		return encoded
			.replace("!", "%21")
			.replace("'", "%27")
			.replace("(", "%28")
			.replace(")", "%29");
	}

	private String randomHex(int bytes) {
		byte[] buffer = new byte[bytes];
		random.nextBytes(buffer);
		return Hex.encodeHexString(buffer);
	}

	private JsonBrowser normalize(JsonBrowser json) {
		if (json == null || json.isNull()) return null;
		JsonBrowser data = json.get("results");
		if (data == null || data.isNull()) data = json.get("result");
		if (data == null || data.isNull()) data = json;
		if (data != null && !data.isNull() && data.isList() && !data.values().isEmpty()) {
			return data.index(0);
		}
		return data;
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
		if (node == null || node.isNull()) return null;
		return getText(node.get(field));
	}

	private String getText(JsonBrowser node) {
		if (node == null || node.isNull()) return null;
		String text = node.text();
		return text != null && !text.isEmpty() ? text : null;
	}

	private String trimSlashes(String path) {
		if (path == null) return "";
		String trimmed = path;
		while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
		while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
		return trimmed;
	}

	private String deriveNameFromPath(String path, ExtendedAudioPlaylist.Type type) {
		if (path == null || path.isEmpty()) {
			return "Audiomack " + type.name().toLowerCase(Locale.ROOT);
		}
		String[] parts = trimSlashes(path).split("/");
		String slug = parts.length > 0 ? parts[parts.length - 1] : "audiomack";
		String name = slug.replace("-", " ").trim();
		if (name.isEmpty()) {
			name = "Audiomack " + type.name().toLowerCase(Locale.ROOT);
		}
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	@Override
	public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
		this.httpInterfaceManager.configureRequests(configurator);
	}

	@Override
	public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
		this.httpInterfaceManager.configureBuilder(configurator);
	}

	@Override
	public void shutdown() {
		try {
			this.httpInterfaceManager.close();
		} catch (IOException e) {
			log.error("Failed to close HTTP interface manager", e);
		}
	}

	public HttpInterface getHttpInterface() {
		return this.httpInterfaceManager.getInterface();
	}

	public static class AudiomackConfig {
		private static final int DEFAULT_SEARCH_LIMIT = 10;
		private static final int DEFAULT_ARTIST_TRACK_LIMIT = 25;

		private int searchLimit = DEFAULT_SEARCH_LIMIT;
		private int artistTrackLimit = DEFAULT_ARTIST_TRACK_LIMIT;

		public int getSearchLimit() {
			return searchLimit;
		}

		public void setSearchLimit(int searchLimit) {
			if (searchLimit < 1) {
				throw new IllegalArgumentException("searchLimit must be greater than 0");
			}
			this.searchLimit = searchLimit;
		}

		public int getArtistTrackLimit() {
			return artistTrackLimit;
		}

		public void setArtistTrackLimit(int artistTrackLimit) {
			if (artistTrackLimit < 1) {
				throw new IllegalArgumentException("artistTrackLimit must be greater than 0");
			}
			this.artistTrackLimit = artistTrackLimit;
		}
	}
}
