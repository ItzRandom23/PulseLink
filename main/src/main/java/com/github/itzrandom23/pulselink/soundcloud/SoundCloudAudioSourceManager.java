package com.github.itzrandom23.pulselink.soundcloud;

import com.github.itzrandom23.pulselink.ExtendedAudioPlaylist;
import com.github.itzrandom23.pulselink.PulseLinkTools;
import com.github.itzrandom23.pulselink.mirror.MirroringAudioSourceManager;
import com.github.itzrandom23.pulselink.mirror.MirroringAudioTrackResolver;
import com.github.topi314.lavasearch.AudioSearchManager;
import com.github.topi314.lavasearch.result.AudioSearchResult;
import com.github.topi314.lavasearch.result.BasicAudioSearchResult;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.config.RequestConfig;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

public class SoundCloudAudioSourceManager extends MirroringAudioSourceManager implements HttpConfigurable, AudioSearchManager {

	private static final Logger log = LoggerFactory.getLogger(SoundCloudAudioSourceManager.class);

	private static final String BASE_URL = "https://api-v2.soundcloud.com";
	private static final String SOUNDCLOUD_URL = "https://soundcloud.com";
	private static final String SEARCH_PREFIX = "scsearch:";
	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/122 Safari/537.36";
	private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("(?:[?&/]?(?:client_id)[\\s:=&]*\"?|\"data\":\\{\"id\":\")([A-Za-z0-9]{32})\"?");
	private static final Pattern ASSET_PATTERN = Pattern.compile("https://a-v2\\.sndcdn\\.com/assets/[a-zA-Z0-9-]+\\.js");
	private static final Set<AudioSearchResult.Type> SEARCH_TYPES = Set.of(AudioSearchResult.Type.TRACK);

	private final SoundCloudConfig config;
	private volatile String clientId;

	public SoundCloudAudioSourceManager(@NotNull SoundCloudConfig config, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver resolver) {
		super(audioPlayerManager, resolver);
		this.config = config;
	}

	@NotNull
	@Override
	public String getSourceName() {
		return "soundcloud";
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
			return new BasicAudioSearchResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
		} catch (IOException e) {
			throw new RuntimeException("Failed to search SoundCloud", e);
		}
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
		try {
			String identifier = reference.identifier;
			if (identifier.startsWith(SEARCH_PREFIX)) {
				return getSearch(identifier.substring(SEARCH_PREFIX.length()));
			}
			if (!isSoundCloudUrl(identifier)) {
				return null;
			}
			return resolveUrl(identifier);
		} catch (IOException e) {
			throw new RuntimeException("Failed to load SoundCloud item", e);
		}
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
		var extended = super.decodeTrack(input);
		return new SoundCloudAudioTrack(
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

	SoundCloudStreamInfo getStreamInfo(HttpInterface httpInterface, String identifier) throws IOException {
		String clientId = getClientId();
		JsonBrowser track = getJson(httpInterface, BASE_URL + "/tracks/" + encode(identifier) + "?client_id=" + encode(clientId));
		if (track == null || track.isNull()) {
			return null;
		}

		JsonBrowser transcodings = track.get("media").get("transcodings");
		if (transcodings.isNull() || transcodings.values().isEmpty()) {
			return null;
		}

		JsonBrowser selected = selectBestTranscoding(transcodings.values());
		String transcodingUrl = getText(selected, "url");
		if (transcodingUrl == null) {
			return null;
		}

		JsonBrowser streamInfo = getJson(httpInterface, transcodingUrl + "?client_id=" + encode(clientId));
		String streamUrl = getText(streamInfo, "url");
		if (streamUrl == null || streamUrl.contains("cf-preview-media") || streamUrl.contains("/preview/")) {
			return null;
		}

		String protocol = getText(selected.get("format"), "protocol");
		String mimeType = getText(selected.get("format"), "mime_type");
		Quality quality = detectQuality(selected);
		return new SoundCloudStreamInfo(streamUrl, "hls".equalsIgnoreCase(protocol), mimeType, quality.codec(), quality.bitrate());
	}

	List<String> resolveHlsParts(HttpInterface httpInterface, String masterUrl) throws IOException {
		String master = fetchText(httpInterface, masterUrl);
		String mediaUrl = firstMediaUrl(master, masterUrl);
		String media = fetchText(httpInterface, mediaUrl);

		List<String> parts = new ArrayList<>();
		for (String rawLine : media.split("\n")) {
			String line = rawLine.trim();
			if (line.startsWith("#EXT-X-MAP:")) {
				String init = extractQuotedUri(line);
				if (init != null) {
					parts.add(resolveUrl(mediaUrl, init));
				}
				continue;
			}
			if (!line.isEmpty() && !line.startsWith("#")) {
				parts.add(resolveUrl(mediaUrl, line));
			}
		}
		return parts;
	}

	HttpGet createRequest(String url) {
		HttpGet request = new HttpGet(url);
		request.setHeader("User-Agent", USER_AGENT);
		request.setHeader("Accept", "*/*");
		request.setHeader("Origin", SOUNDCLOUD_URL);
		request.setHeader("Referer", SOUNDCLOUD_URL + "/");
		request.setConfig(RequestConfig.custom()
			.setConnectTimeout(10000)
			.setConnectionRequestTimeout(10000)
			.setSocketTimeout(20000)
			.build());
		return request;
	}

	public HttpInterface getHttpInterface() {
		return this.httpInterfaceManager.getInterface();
	}

	private AudioItem getSearch(String query) throws IOException {
		String url = BASE_URL + "/search/tracks?q=" + encode(query) + "&client_id=" + encode(getClientId()) + "&limit=" + Math.max(1, this.config.getSearchLimit());
		JsonBrowser json = getJson(url);
		JsonBrowser collection = json != null ? json.get("collection") : null;
		List<AudioTrack> tracks = parseTracks(collection);
		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}
		return new BasicAudioPlaylist("SoundCloud Search: " + query, tracks, null, true);
	}

	private AudioItem resolveUrl(String soundCloudUrl) throws IOException {
		String resolvedSoundCloudUrl = expandShortUrl(soundCloudUrl);
		JsonBrowser data = getJson(BASE_URL + "/resolve?url=" + encode(resolvedSoundCloudUrl) + "&client_id=" + encode(getClientId()));
		if (data == null || data.isNull()) {
			return AudioReference.NO_TRACK;
		}

		String kind = getText(data, "kind");
		if ("track".equalsIgnoreCase(kind)) {
			AudioTrack track = parseTrack(data);
			return track != null ? track : AudioReference.NO_TRACK;
		}
		if ("playlist".equalsIgnoreCase(kind)) {
			List<AudioTrack> tracks = parseTracks(data.get("tracks"));
			if (tracks.isEmpty()) {
				return AudioReference.NO_TRACK;
			}
			return new ExtendedAudioPlaylist(
				orDefault(getText(data, "title"), "SoundCloud Playlist"),
				tracks,
				ExtendedAudioPlaylist.Type.PLAYLIST,
				orDefault(getText(data, "permalink_url"), resolvedSoundCloudUrl),
				artwork(getText(data, "artwork_url")),
				getText(data.get("user"), "username"),
				(int) data.get("track_count").asLong(tracks.size())
			);
		}
		if ("user".equalsIgnoreCase(kind)) {
			return resolveUser(data, resolvedSoundCloudUrl);
		}

		return AudioReference.NO_TRACK;
	}

	private AudioItem resolveUser(JsonBrowser user, String originalUrl) throws IOException {
		String userId = getText(user, "id");
		if (userId == null) {
			return AudioReference.NO_TRACK;
		}

		String url = BASE_URL + "/users/" + encode(userId) + "/tracks?client_id=" + encode(getClientId()) + "&limit=" + Math.max(1, this.config.getUserTrackLimit());
		List<AudioTrack> tracks = parseTracks(getJson(url));
		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}
		return new ExtendedAudioPlaylist(
			orDefault(getText(user, "username"), "SoundCloud User"),
			tracks,
			ExtendedAudioPlaylist.Type.ARTIST,
			orDefault(getText(user, "permalink_url"), originalUrl),
			artwork(getText(user, "avatar_url")),
			getText(user, "username"),
			tracks.size()
		);
	}

	private List<AudioTrack> parseTracks(JsonBrowser list) throws IOException {
		if (list == null || list.isNull()) {
			return Collections.emptyList();
		}

		List<AudioTrack> tracks = new ArrayList<>();
		for (JsonBrowser item : list.values()) {
			AudioTrack track = parseTrack(item);
			if (track != null) {
				tracks.add(track);
			}
		}
		return tracks;
	}

	private AudioTrack parseTrack(JsonBrowser track) throws IOException {
		if (track == null || track.isNull()) {
			return null;
		}

		String id = getText(track, "id");
		if (id == null) {
			return null;
		}

		String title = orDefault(getText(track, "title"), "Unknown title");
		String author = orDefault(getText(track.get("user"), "username"), "Unknown artist");
		long duration = track.get("duration").asLong(0L);
		String url = getText(track, "permalink_url");
		String artworkUrl = artwork(getText(track, "artwork_url"));
		String artistUrl = getText(track.get("user"), "permalink_url");
		String artistArtworkUrl = artwork(getText(track.get("user"), "avatar_url"));
		String isrc = getText(track.get("publisher_metadata"), "isrc");

		AudioTrackInfo info = new AudioTrackInfo(
			title,
			author,
			duration,
			id,
			false,
			url != null ? url : SOUNDCLOUD_URL + "/tracks/" + id,
			artworkUrl,
			isrc
		);
		var soundCloudTrack = new SoundCloudAudioTrack(info, null, null, artistUrl, artistArtworkUrl, null, false, this);
		return withMirrorDurationIfNeeded(soundCloudTrack);
	}

	private AudioTrack withMirrorDurationIfNeeded(SoundCloudAudioTrack track) throws IOException {
		try (HttpInterface httpInterface = this.httpInterfaceManager.getInterface()) {
			SoundCloudStreamInfo streamInfo = getStreamInfo(httpInterface, track.getIdentifier());
			if (streamInfo != null && streamInfo.streamUrl() != null && !streamInfo.streamUrl().isBlank()) {
				return track;
			}
		} catch (IOException exception) {
			log.debug("SoundCloud direct stream check failed for {}, trying mirror providers.", track.getInfo().uri, exception);
		}

		AudioItem mirrored;
		try {
			mirrored = this.resolver.apply(track);
		} catch (Exception exception) {
			log.debug("SoundCloud mirror resolution failed for {}.", track.getInfo().uri, exception);
			return null;
		}
		AudioTrack mirrorTrack = null;
		if (mirrored instanceof AudioPlaylist playlist && !playlist.getTracks().isEmpty()) {
			mirrorTrack = playlist.getTracks().get(0);
		} else if (mirrored instanceof AudioTrack audioTrack) {
			mirrorTrack = audioTrack;
		}

		if (mirrorTrack != null && mirrorTrack.getInfo().length > 0) {
			AudioTrackInfo info = new AudioTrackInfo(
				track.getInfo().title,
				track.getInfo().author,
				mirrorTrack.getInfo().length,
				track.getIdentifier(),
				false,
				track.getInfo().uri,
				track.getInfo().artworkUrl,
				track.getInfo().isrc
			);
			return new SoundCloudAudioTrack(
				info,
				track.getAlbumName(),
				track.getAlbumUrl(),
				track.getArtistUrl(),
				track.getArtistArtworkUrl(),
				track.getPreviewUrl(),
				track.isPreview(),
				this
			);
		}
		return null;
	}

	private JsonBrowser selectBestTranscoding(List<JsonBrowser> transcodings) {
		for (JsonBrowser transcoding : transcodings) {
			if ("progressive".equalsIgnoreCase(getText(transcoding.get("format"), "protocol"))) {
				return transcoding;
			}
		}
		for (JsonBrowser transcoding : transcodings) {
			if (isHls(transcoding) && isAac160(transcoding)) {
				return transcoding;
			}
		}
		for (JsonBrowser transcoding : transcodings) {
			if (isHls(transcoding) && isAac96(transcoding)) {
				return transcoding;
			}
		}
		return transcodings.get(0);
	}

	private boolean isHls(JsonBrowser transcoding) {
		return "hls".equalsIgnoreCase(getText(transcoding.get("format"), "protocol"));
	}

	private boolean isAac160(JsonBrowser transcoding) {
		String url = orDefault(getText(transcoding, "url"), "");
		String preset = orDefault(getText(transcoding, "preset"), "");
		String quality = orDefault(getText(transcoding, "quality"), "");
		return url.contains("aac_160") || preset.contains("160") || "hq".equalsIgnoreCase(quality);
	}

	private boolean isAac96(JsonBrowser transcoding) {
		String url = orDefault(getText(transcoding, "url"), "");
		String preset = orDefault(getText(transcoding, "preset"), "");
		return url.contains("aac_96") || preset.contains("96");
	}

	private Quality detectQuality(JsonBrowser transcoding) {
		String url = orDefault(getText(transcoding, "url"), "").toLowerCase(Locale.ROOT);
		String mime = orDefault(getText(transcoding.get("format"), "mime_type"), "").toLowerCase(Locale.ROOT);
		String preset = orDefault(getText(transcoding, "preset"), "").toLowerCase(Locale.ROOT);
		String quality = orDefault(getText(transcoding, "quality"), "").toLowerCase(Locale.ROOT);
		if (url.contains("aac_160") || preset.contains("160") || "hq".equals(quality)) {
			return new Quality("AAC", 160);
		}
		if (url.contains("aac_96") || preset.contains("96")) {
			return new Quality("AAC", 96);
		}
		if (mime.contains("mpeg")) {
			return new Quality("MP3", 128);
		}
		if (mime.contains("opus")) {
			return new Quality("OPUS", 64);
		}
		return new Quality(null, null);
	}

	private String getClientId() throws IOException {
		String cached = this.clientId;
		if (cached != null) {
			return cached;
		}
		synchronized (this) {
			if (this.clientId != null) {
				return this.clientId;
			}

			String html = fetchText(SOUNDCLOUD_URL);
			String direct = findClientId(html);
			if (direct != null) {
				this.clientId = direct;
				return direct;
			}

			LinkedHashSet<String> assets = new LinkedHashSet<>();
			var matcher = ASSET_PATTERN.matcher(html);
			while (matcher.find()) {
				assets.add(matcher.group());
			}
			for (String asset : assets) {
				try {
					String js = fetchText(asset);
					String clientId = findClientId(js);
					if (clientId != null) {
						this.clientId = clientId;
						return clientId;
					}
				} catch (IOException ignored) {
					// Try the next SoundCloud asset.
				}
			}
		}
		throw new FriendlyException("Failed to fetch SoundCloud client id.", FriendlyException.Severity.SUSPICIOUS, null);
	}

	private String findClientId(String text) {
		if (text == null) {
			return null;
		}
		var matcher = CLIENT_ID_PATTERN.matcher(text);
		return matcher.find() ? matcher.group(1) : null;
	}

	private JsonBrowser getJson(String url) throws IOException {
		try (HttpInterface httpInterface = this.httpInterfaceManager.getInterface()) {
			return getJson(httpInterface, url);
		}
	}

	private JsonBrowser getJson(HttpInterface httpInterface, String url) throws IOException {
		return PulseLinkTools.fetchResponseAsJson(httpInterface, createRequest(url));
	}

	private String expandShortUrl(String url) throws IOException {
		if (!isShortSoundCloudUrl(url)) {
			return url;
		}

		String current = url;
		try (HttpInterface httpInterface = this.httpInterfaceManager.getInterface()) {
			for (int redirectCount = 0; redirectCount < 8; redirectCount++) {
				HttpGet request = createRequest(current);
				request.setConfig(RequestConfig.copy(request.getConfig())
					.setRedirectsEnabled(false)
					.build());

				try (CloseableHttpResponse response = httpInterface.execute(request)) {
					int status = response.getStatusLine().getStatusCode();
					if (!isRedirect(status)) {
						return current;
					}

					var location = response.getFirstHeader("Location");
					if (location == null || location.getValue() == null || location.getValue().isBlank()) {
						return current;
					}

					current = resolveUrl(current, location.getValue().trim());
					if (isCanonicalSoundCloudUrl(current)) {
						return current;
					}
				}
			}
		}

		return current;
	}

	private String fetchText(String url) throws IOException {
		try (HttpInterface httpInterface = this.httpInterfaceManager.getInterface()) {
			return fetchText(httpInterface, url);
		}
	}

	private String fetchText(HttpInterface httpInterface, String url) throws IOException {
		try (CloseableHttpResponse response = httpInterface.execute(createRequest(url))) {
			int status = response.getStatusLine().getStatusCode();
			if (status == HttpStatus.SC_NOT_FOUND || !HttpClientTools.isSuccessWithContent(status)) {
				throw new IOException("SoundCloud request failed with status " + status + ": " + url);
			}
			return IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
		}
	}

	private String firstMediaUrl(String playlistBody, String baseUrl) {
		for (String line : playlistBody.split("\n")) {
			String trimmed = line.trim();
			if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
				return resolveUrl(baseUrl, trimmed);
			}
		}
		return baseUrl;
	}

	private String extractQuotedUri(String line) {
		int start = line.indexOf("URI=\"");
		if (start < 0) {
			return null;
		}
		start += 5;
		int end = line.indexOf('"', start);
		return end > start ? line.substring(start, end) : null;
	}

	private String resolveUrl(String base, String path) {
		try {
			return URI.create(base).resolve(path).toString();
		} catch (IllegalArgumentException ignored) {
			return path;
		}
	}

	private boolean isRedirect(int status) {
		return status == HttpStatus.SC_MOVED_PERMANENTLY
			|| status == HttpStatus.SC_MOVED_TEMPORARILY
			|| status == HttpStatus.SC_SEE_OTHER
			|| status == HttpStatus.SC_TEMPORARY_REDIRECT
			|| status == 308;
	}

	private boolean isSoundCloudUrl(String identifier) {
		if (identifier == null || !identifier.startsWith("http")) {
			return false;
		}
		try {
			String host = URI.create(identifier).getHost();
			if (host == null) {
				return false;
			}
			host = host.toLowerCase(Locale.ROOT);
			return host.equals("soundcloud.com")
				|| host.equals("www.soundcloud.com")
				|| host.equals("m.soundcloud.com")
				|| host.equals("on.soundcloud.com")
				|| host.equals("snd.sc");
		} catch (IllegalArgumentException ignored) {
			return false;
		}
	}

	private boolean isShortSoundCloudUrl(String identifier) {
		String host = getHost(identifier);
		return host != null && (host.equals("on.soundcloud.com") || host.equals("snd.sc"));
	}

	private boolean isCanonicalSoundCloudUrl(String identifier) {
		String host = getHost(identifier);
		return host != null && (host.equals("soundcloud.com") || host.equals("www.soundcloud.com") || host.equals("m.soundcloud.com"));
	}

	private String getHost(String identifier) {
		try {
			String host = URI.create(identifier).getHost();
			return host != null ? host.toLowerCase(Locale.ROOT) : null;
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private String artwork(String url) {
		return url != null ? url.replace("-large", "-t500x500") : null;
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

	private String orDefault(String value, String fallback) {
		return value != null && !value.isBlank() ? value : fallback;
	}

	private String encode(String value) {
		return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8);
	}

	@Override
	public void shutdown() {
		try {
			this.httpInterfaceManager.close();
		} catch (IOException e) {
			log.error("Failed to close SoundCloud HTTP interface manager", e);
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

	record Quality(String codec, Integer bitrate) {
	}

	static final class SoundCloudStreamInfo {
		private final String streamUrl;
		private final boolean hls;
		private final String mimeType;
		private final String codec;
		private final Integer bitrate;

		SoundCloudStreamInfo(String streamUrl, boolean hls, String mimeType, String codec, Integer bitrate) {
			this.streamUrl = streamUrl;
			this.hls = hls;
			this.mimeType = mimeType;
			this.codec = codec;
			this.bitrate = bitrate;
		}

		String streamUrl() {
			return this.streamUrl;
		}

		boolean hls() {
			return this.hls;
		}

		String mimeType() {
			return this.mimeType;
		}

		String codec() {
			return this.codec;
		}

		Integer bitrate() {
			return this.bitrate;
		}
	}

	public static class SoundCloudConfig {
		private int searchLimit = 10;
		private int userTrackLimit = 25;

		public int getSearchLimit() {
			return this.searchLimit;
		}

		public void setSearchLimit(int searchLimit) {
			if (searchLimit < 1) {
				throw new IllegalArgumentException("searchLimit must be greater than 0");
			}
			this.searchLimit = searchLimit;
		}

		public int getUserTrackLimit() {
			return this.userTrackLimit;
		}

		public void setUserTrackLimit(int userTrackLimit) {
			if (userTrackLimit < 1) {
				throw new IllegalArgumentException("userTrackLimit must be greater than 0");
			}
			this.userTrackLimit = userTrackLimit;
		}
	}
}
