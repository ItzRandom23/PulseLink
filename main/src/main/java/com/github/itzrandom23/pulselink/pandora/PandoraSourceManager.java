package com.github.itzrandom23.pulselink.pandora;

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
import org.apache.http.Header;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PandoraSourceManager extends MirroringAudioSourceManager implements HttpConfigurable, AudioSearchManager {

	private static final Logger log = LoggerFactory.getLogger(PandoraSourceManager.class);

	public static final String SEARCH_PREFIX = "pdsearch:";
	public static final Pattern URL_PATTERN = Pattern.compile(
		"^https?://(?:www\\.)?pandora\\.com/(?<type>playlist|station|podcast|artist)/.+$",
		Pattern.CASE_INSENSITIVE
	);
	public static final Set<AudioSearchResult.Type> SEARCH_TYPES = Set.of(AudioSearchResult.Type.TRACK);

	private volatile String csrfToken;
	private volatile String authToken;
	private volatile String csrfTokenConfig;
	private volatile String remoteTokenUrl;
	private volatile String providedAuthToken;

	public PandoraSourceManager(String[] providers, AudioPlayerManager audioPlayerManager) {
		this(providers, unused -> audioPlayerManager);
	}

	public PandoraSourceManager(String[] providers, Function<Void, AudioPlayerManager> audioPlayerManager) {
		this(audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers));
	}

	public PandoraSourceManager(Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver resolver) {
		super(audioPlayerManager, resolver);
		this.httpInterfaceManager.configureRequests(config -> RequestConfig.copy(config)
			.setConnectTimeout(10000)
			.setConnectionRequestTimeout(10000)
			.setSocketTimeout(15000)
			.build());
	}

	public void setCsrfToken(String csrfTokenConfig) {
		this.csrfTokenConfig = blankToNull(csrfTokenConfig);
		this.csrfToken = null;
	}

	public void setAuthToken(String authToken) {
		this.providedAuthToken = blankToNull(authToken);
		this.authToken = this.providedAuthToken;
	}

	public void setRemoteTokenUrl(String remoteTokenUrl) {
		this.remoteTokenUrl = blankToNull(remoteTokenUrl);
	}

	@NotNull
	@Override
	public String getSourceName() {
		return "pandora";
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
		var extended = super.decodeTrack(input);
		return new PandoraAudioTrack(
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
	public @Nullable AudioSearchResult loadSearch(@NotNull String query, @NotNull Set<AudioSearchResult.Type> types) {
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
		} catch (IOException e) {
			log.error("Pandora search failed", e);
			return null;
		}
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
		try {
			String identifier = reference.identifier;
			if (identifier.startsWith(SEARCH_PREFIX)) {
				var tracks = searchTracks(identifier.substring(SEARCH_PREFIX.length()).trim());
				return tracks.isEmpty() ? AudioReference.NO_TRACK : tracks.get(0);
			}

			Matcher matcher = URL_PATTERN.matcher(identifier);
			if (!matcher.matches()) {
				return null;
			}

			String type = matcher.group("type").toLowerCase(Locale.ROOT);
			String id = lastSegment(identifier);

			return switch (type) {
				case "artist" -> resolveArtist(id, identifier);
				case "playlist" -> resolvePlaylist(id, identifier);
				case "station" -> resolveStation(id, identifier);
				case "podcast" -> resolvePodcast(id, identifier);
				default -> null;
			};
		} catch (IOException e) {
			throw new RuntimeException("Failed to load Pandora item", e);
		}
	}

	private ArrayList<AudioTrack> searchTracks(String query) throws IOException {
		if (query == null || query.isBlank()) {
			return new ArrayList<>();
		}

		ensureAuth();

		String payload = "{"
			+ "\"query\":" + quote(query) + ","
			+ "\"types\":[\"TR\"],"
			+ "\"listener\":null,"
			+ "\"start\":0,"
			+ "\"count\":10,"
			+ "\"annotate\":true,"
			+ "\"searchTime\":0,"
			+ "\"annotationRecipe\":\"CLASS_OF_2019\""
			+ "}";

		JsonBrowser data = postJson("https://www.pandora.com/api/v3/sod/search", payload, false);
		if (data == null || data.isNull()) {
			return new ArrayList<>();
		}

		ArrayList<AudioTrack> tracks = new ArrayList<>();
		JsonBrowser annotations = data.get("annotations");
		if (!annotations.isNull()) {
			for (JsonBrowser item : annotations.values()) {
				if ("TR".equals(item.get("type").safeText())) {
					var track = buildTrack(item);
					if (track != null) {
						tracks.add(track);
					}
				}
			}
		}
		return tracks;
	}

	private AudioItem resolveArtist(String id, String url) throws IOException {
		ensureAuth();

		String annotatePayload = "{\"pandoraIds\":[" + quote(id) + "]}";
		JsonBrowser data = postJson("https://www.pandora.com/api/v4/catalog/annotateObjectsSimple", annotatePayload, true);
		if (data == null || data.isNull()) {
			return AudioReference.NO_TRACK;
		}

		for (JsonBrowser item : data.values()) {
			String type = item.get("type").safeText();
			if ("TR".equals(type)) {
				var track = buildTrack(item);
				return track != null ? track : AudioReference.NO_TRACK;
			}
			if ("AL".equals(type)) {
				return resolveAlbumDetails(item.get("pandoraId").safeText(), item.get("name").safeText(), url);
			}
			if ("AR".equals(type)) {
				return resolveArtistDetails(item.get("pandoraId").safeText(), url);
			}
		}

		return AudioReference.NO_TRACK;
	}

	private AudioItem resolveAlbumDetails(String id, String name, String url) throws IOException {
		String payload = "{\"pandoraId\":" + quote(id) + "}";
		JsonBrowser data = postJson("https://www.pandora.com/api/v4/catalog/getDetails", payload, true);
		if (data == null || data.isNull()) {
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = new ArrayList<>();
		JsonBrowser annotations = data.get("annotations");
		if (!annotations.isNull()) {
			for (JsonBrowser item : annotations.values()) {
				var track = buildTrack(item);
				if (track != null) {
					tracks.add(track);
				}
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new PandoraAudioPlaylist(
			firstNonBlank(name, "Album"),
			tracks,
			ExtendedAudioPlaylist.Type.ALBUM,
			url,
			null,
			getFirstAuthor(tracks),
			tracks.size()
		);
	}

	private AudioItem resolveArtistDetails(String id, String url) throws IOException {
		String payload = "{"
			+ "\"operationName\":\"GetArtistDetailsWithCuratorsWeb\","
			+ "\"query\":" + quote("query GetArtistDetailsWithCuratorsWeb($pandoraId: String!) { entity(id: $pandoraId) { ... on Artist { name topTracksWithCollaborations { ...TrackFragment __typename } __typename } } } fragment ArtFragment on Art { artId dominantColor artUrl: url(size: WIDTH_500) } fragment TrackFragment on Track { pandoraId: id type name duration shareableUrlPath: urlPath artistName: artist { name __typename } icon: art { ...ArtFragment __typename } }") + ","
			+ "\"variables\":{\"pandoraId\":" + quote(id) + "}"
			+ "}";
		JsonBrowser data = postJson("https://www.pandora.com/api/v1/graphql/graphql", payload, true);
		JsonBrowser entity = data != null ? data.get("data").get("entity") : null;
		if (entity == null || entity.isNull()) {
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = new ArrayList<>();
		for (JsonBrowser item : entity.get("topTracksWithCollaborations").values()) {
			var track = buildTrack(item);
			if (track != null) {
				tracks.add(track);
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		String artistName = firstNonBlank(entity.get("name").safeText(), "Artist");
		return new PandoraAudioPlaylist(
			artistName + "'s Top Tracks",
			tracks,
			ExtendedAudioPlaylist.Type.ARTIST,
			url,
			null,
			artistName,
			tracks.size()
		);
	}

	private AudioItem resolvePlaylist(String id, String url) throws IOException {
		ensureAuth();

		String payload = "{"
			+ "\"request\":{"
			+ "\"pandoraId\":" + quote(id) + ","
			+ "\"playlistVersion\":0,"
			+ "\"offset\":0,"
			+ "\"limit\":100,"
			+ "\"annotationLimit\":100,"
			+ "\"allowedTypes\":[\"TR\",\"AM\"],"
			+ "\"bypassPrivacyRules\":true"
			+ "}"
			+ "}";

		JsonBrowser data = postJson("https://www.pandora.com/api/v7/playlists/getTracks", payload, true);
		if (data == null || data.isNull()) {
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = new ArrayList<>();
		JsonBrowser annotations = data.get("annotations");
		if (!annotations.isNull()) {
			for (JsonBrowser item : annotations.values()) {
				if ("TR".equals(item.get("type").safeText())) {
					var track = buildTrack(item);
					if (track != null) {
						tracks.add(track);
					}
				}
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new PandoraAudioPlaylist(
			firstNonBlank(data.get("name").safeText(), "Playlist"),
			tracks,
			ExtendedAudioPlaylist.Type.PLAYLIST,
			url,
			null,
			getFirstAuthor(tracks),
			tracks.size()
		);
	}

	private AudioItem resolveStation(String id, String url) throws IOException {
		ensureAuth();

		JsonBrowser station = postJson("https://www.pandora.com/api/v1/station/getStationDetails", "{\"stationId\":" + quote(id) + "}", true);
		if (station == null || station.isNull()) {
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = fetchStationTracks(id, station);
		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new PandoraAudioPlaylist(
			firstNonBlank(station.get("name").safeText(), "Station"),
			tracks,
			ExtendedAudioPlaylist.Type.PLAYLIST,
			url,
			null,
			getFirstAuthor(tracks),
			tracks.size()
		);
	}

	private List<AudioTrack> fetchStationTracks(String id, JsonBrowser stationData) throws IOException {
		List<AudioTrack> tracks = new ArrayList<>();

		JsonBrowser playlistData = postJson("https://www.pandora.com/api/v1/playlist/getPlaylist", "{\"stationId\":" + quote(id) + "}", true);
		if (playlistData != null && playlistData.get("items").isList()) {
			for (JsonBrowser item : playlistData.get("items").values()) {
				if (item.get("songName").isNull()) {
					continue;
				}
				var track = buildTrackFromFields(
					item.get("songId").safeText(),
					item.get("songName").safeText(),
					item.get("artistName").safeText(),
					item.get("songDetailUrl").safeText(),
					item.get("albumArtUrl").safeText(),
					item.get("trackLength").asLong(0L),
					null
				);
				if (track != null) {
					tracks.add(track);
				}
			}
		}

		if (!tracks.isEmpty()) {
			return tracks;
		}

		for (JsonBrowser seed : stationData.get("seeds").values()) {
			JsonBrowser song = seed.get("song");
			if (song.isNull()) {
				continue;
			}
			String artUrl = null;
			JsonBrowser artArray = seed.get("art");
			if (artArray.isList() && !artArray.values().isEmpty()) {
				artUrl = artArray.values().get(artArray.values().size() - 1).get("url").safeText();
			}
			var track = buildTrackFromFields(
				song.get("songId").safeText(),
				song.get("songTitle").safeText(),
				song.get("artistSummary").safeText(),
				song.get("songDetailUrl").safeText(),
				artUrl,
				0L,
				null
			);
			if (track != null) {
				tracks.add(track);
			}
		}
		return tracks;
	}

	private AudioItem resolvePodcast(String id, String url) throws IOException {
		ensureAuth();

		JsonBrowser data = postJson("https://www.pandora.com/api/v1/aesop/getDetails", "{\"catalogVersion\":4,\"pandoraId\":" + quote(id) + "}", true);
		JsonBrowser details = data != null ? data.get("details") : null;
		if (details == null || details.isNull()) {
			return AudioReference.NO_TRACK;
		}

		String type = null;
		if (!details.get("podcastProgramDetails").isNull()) {
			type = details.get("podcastProgramDetails").get("type").safeText();
		} else if (!details.get("podcastEpisodeDetails").isNull()) {
			type = details.get("podcastEpisodeDetails").get("type").safeText();
		}

		if ("PE".equals(type)) {
			String episodeId = details.get("podcastEpisodeDetails").get("pandoraId").safeText();
			var track = buildTrack(data.get("annotations").get(episodeId));
			return track != null ? track : AudioReference.NO_TRACK;
		}
		if ("PC".equals(type)) {
			return resolvePodcastEpisodes(id, url);
		}
		return AudioReference.NO_TRACK;
	}

	private AudioItem resolvePodcastEpisodes(String id, String url) throws IOException {
		JsonBrowser idsData = postJson("https://www.pandora.com/api/v1/aesop/getAllEpisodesByPodcastProgram", "{\"catalogVersion\":4,\"pandoraId\":" + quote(id) + "}", true);
		if (idsData == null || idsData.isNull()) {
			return AudioReference.NO_TRACK;
		}

		List<String> episodeIds = new ArrayList<>();
		for (JsonBrowser yearInfo : idsData.get("episodes").get("episodesWithLabel").values()) {
			for (JsonBrowser episodeId : yearInfo.get("episodes").values()) {
				String value = episodeId.safeText();
				if (value != null && !value.isBlank()) {
					episodeIds.add(value);
				}
			}
		}
		if (episodeIds.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		StringBuilder payload = new StringBuilder("{\"catalogVersion\":4,\"pandoraIds\":[");
		for (int i = 0; i < episodeIds.size(); i++) {
			if (i > 0) {
				payload.append(',');
			}
			payload.append(quote(episodeIds.get(i)));
		}
		payload.append("]}");

		JsonBrowser allEpisodes = postJson("https://www.pandora.com/api/v1/aesop/annotateObjects", payload.toString(), true);
		if (allEpisodes == null || allEpisodes.isNull()) {
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = new ArrayList<>();
		String programName = "Podcast";
		JsonBrowser annotations = allEpisodes.get("annotations");
		for (JsonBrowser item : annotations.values()) {
			if ("PC".equals(item.get("type").safeText())) {
				programName = firstNonBlank(item.get("name").safeText(), programName);
				continue;
			}
			var track = buildTrack(item);
			if (track != null) {
				tracks.add(track);
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new PandoraAudioPlaylist(
			programName,
			tracks,
			ExtendedAudioPlaylist.Type.PLAYLIST,
			url,
			null,
			getFirstAuthor(tracks),
			tracks.size()
		);
	}

	private PandoraAudioTrack buildTrack(JsonBrowser item) {
		if (item == null || item.isNull()) {
			return null;
		}

		String id = firstNonBlank(item.get("pandoraId").safeText(), item.get("id").safeText());
		String title = firstNonBlank(item.get("name").safeText(), "Unknown Title");
		String author = firstNonBlank(item.get("artistName").safeText(), item.get("programName").safeText(), nestedName(item.get("artistName")), "Unknown Artist");
		String uri = buildUri(firstNonBlank(item.get("shareableUrlPath").safeText(), item.get("urlPath").safeText()));
		String artworkUrl = buildArtworkUrl(firstNonBlank(item.get("icon").get("artUrl").safeText(), item.get("icon").get("url").safeText()));
		String isrc = item.get("isrc").safeText();
		long durationMs = toMilliseconds(item.get("duration").safeText(), item.get("trackLength").safeText(), item.get("length").safeText());

		return buildTrackFromFields(id, title, author, uri, artworkUrl, durationMs / 1000L, isrc);
	}

	private PandoraAudioTrack buildTrackFromFields(String id, String title, String author, String uri, String artworkUrl, long durationSeconds, String isrc) {
		if (id == null || title == null) {
			return null;
		}

		String finalUri = buildUri(uri);
		long durationMs = durationSeconds > 1000 ? durationSeconds : durationSeconds * 1000L;
		var info = new AudioTrackInfo(
			title,
			firstNonBlank(author, "Unknown Artist"),
			durationMs,
			id,
			false,
			firstNonBlank(finalUri, ""),
			buildArtworkUrl(artworkUrl),
			isrc
		);

		return new PandoraAudioTrack(info, null, null, null, null, null, false, this);
	}

	private synchronized void ensureAuth() throws IOException {
		if (this.authToken != null && this.csrfToken != null) {
			return;
		}

		if (this.providedAuthToken != null && this.csrfTokenConfig != null) {
			this.authToken = this.providedAuthToken;
			this.csrfToken = this.csrfTokenConfig;
			return;
		}

		if (this.remoteTokenUrl != null) {
			JsonBrowser remote = PulseLinkTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), new HttpGet(this.remoteTokenUrl));
			if (remote != null && remote.get("success").asBoolean(false)) {
				String remoteAuthToken = remote.get("authToken").safeText();
				String remoteCsrfToken = remote.get("csrfToken").safeText();
				if (remoteAuthToken != null && remoteCsrfToken != null) {
					this.authToken = remoteAuthToken;
					this.csrfToken = remoteCsrfToken;
					return;
				}
			}
		}

		if (this.csrfToken == null) {
			this.csrfToken = this.csrfTokenConfig != null ? this.csrfTokenConfig : fetchCsrfToken();
		}

		HttpPost login = new HttpPost("https://www.pandora.com/api/v1/auth/anonymousLogin");
		login.setHeader("Cookie", "csrftoken=" + this.csrfToken);
		login.setHeader("Content-Type", "application/json");
		login.setHeader("Accept", "*/*");
		login.setHeader("X-CsrfToken", this.csrfToken);
		login.setEntity(new StringEntity("{}", ContentType.APPLICATION_JSON));

		JsonBrowser response = PulseLinkTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), login);
		String authToken = response != null ? response.get("authToken").safeText() : null;
		if (authToken == null || authToken.isBlank()) {
			throw new IOException("Failed to authenticate with Pandora");
		}
		this.authToken = authToken;
	}

	private String fetchCsrfToken() throws IOException {
		HttpHead request = new HttpHead("https://www.pandora.com");
		try (CloseableHttpResponse response = this.httpInterfaceManager.getInterface().execute(request)) {
			for (Header header : response.getHeaders("Set-Cookie")) {
				String value = header.getValue();
				if (value != null && value.startsWith("csrftoken=")) {
					int end = value.indexOf(';');
					String cookie = end == -1 ? value : value.substring(0, end);
					return cookie.substring("csrftoken=".length());
				}
			}
		}
		throw new IOException("Failed to fetch Pandora CSRF token");
	}

	private JsonBrowser postJson(String url, String jsonPayload, boolean authHeaders) throws IOException {
		HttpPost request = new HttpPost(url);
		request.setHeader("Content-Type", "application/json");
		request.setHeader("Accept", "*/*");
		if (authHeaders) {
			ensureAuth();
			request.setHeader("Cookie", "csrftoken=" + this.csrfToken);
			request.setHeader("X-CsrfToken", this.csrfToken);
			request.setHeader("X-AuthToken", this.authToken);
		}
		request.setEntity(new StringEntity(jsonPayload, ContentType.APPLICATION_JSON));
		return PulseLinkTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
	}

	private String buildArtworkUrl(String artwork) {
		if (artwork == null || artwork.isBlank()) {
			return null;
		}
		return artwork.startsWith("http") ? artwork : "https://content-images.p-cdn.com/" + artwork;
	}

	private String buildUri(String shareableUrlPath) {
		if (shareableUrlPath == null || shareableUrlPath.isBlank()) {
			return "";
		}
		return shareableUrlPath.startsWith("http") ? shareableUrlPath : "https://www.pandora.com" + shareableUrlPath;
	}

	private long toMilliseconds(String... values) {
		for (String value : values) {
			if (value == null || value.isBlank()) {
				continue;
			}
			try {
				double seconds = Double.parseDouble(value);
				return Math.round(seconds * 1000D);
			} catch (NumberFormatException ignored) {
			}
		}
		return 0L;
	}

	private String nestedName(JsonBrowser node) {
		if (node == null || node.isNull()) {
			return null;
		}
		return node.get("name").safeText();
	}

	private String getFirstAuthor(List<AudioTrack> tracks) {
		return tracks.isEmpty() ? null : tracks.get(0).getInfo().author;
	}

	private static String quote(String value) {
		if (value == null) {
			return "null";
		}
		return "\"" + value
			.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r")
			.replace("\t", "\\t") + "\"";
	}

	private static String lastSegment(String url) {
		String normalized = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
		int query = normalized.indexOf('?');
		if (query >= 0) {
			normalized = normalized.substring(0, query);
		}
		return normalized.substring(normalized.lastIndexOf('/') + 1);
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
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
