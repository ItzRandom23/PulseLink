package com.github.itzrandom23.pulselink.gaana;

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
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GaanaAudioSourceManager extends MirroringAudioSourceManager implements HttpConfigurable, AudioSearchManager {

	private static final Logger log = LoggerFactory.getLogger(GaanaAudioSourceManager.class);

	private static final String API_BASE = "https://gaanapi-wine.vercel.app/api";
	private static final String SEARCH_PREFIX = "gasearch:";
	private static final Pattern URL_PATTERN = Pattern.compile("^@?(?:https?://)?(?:www\\.)?gaana\\.com/(?<type>song|album|playlist|artist)/(?<seokey>[\\w-]+)", Pattern.CASE_INSENSITIVE);
	private static final Set<AudioSearchResult.Type> SEARCH_TYPES = Set.of(AudioSearchResult.Type.TRACK);
	private static final int SEARCH_LIMIT = 10;
	private static final int ARTIST_TRACK_LIMIT = 25;
	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";

	public GaanaAudioSourceManager(String[] providers, AudioPlayerManager audioPlayerManager) {
		this(providers, unused -> audioPlayerManager);
	}

	public GaanaAudioSourceManager(String[] providers, Function<Void, AudioPlayerManager> audioPlayerManager) {
		this(audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers));
	}

	public GaanaAudioSourceManager(Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver resolver) {
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
			this
		);
	}

	private AudioItem getSearch(String query) throws IOException {
		String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
		JsonBrowser json = getJson(API_BASE + "/search/songs?q=" + encodedQuery + "&limit=" + SEARCH_LIMIT);
		List<AudioTrack> tracks = parseTrackList(extractList(json));
		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}
		return new BasicAudioPlaylist("Gaana Search: " + query, tracks, null, true);
	}

	private AudioTrack getSong(String seokey) throws IOException {
		JsonBrowser json = getJson(API_BASE + "/songs?seokey=" + URLEncoder.encode(seokey, StandardCharsets.UTF_8));
		return parseTrack(json);
	}

	private AudioItem getAlbum(String seokey) throws IOException {
		JsonBrowser json = getJson(API_BASE + "/albums?seokey=" + URLEncoder.encode(seokey, StandardCharsets.UTF_8));
		if (json == null || json.isNull()) {
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = parseTrackList(json.get("tracks"));
		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new ExtendedAudioPlaylist(
			getFirstText(json, "title", "name"),
			tracks,
			ExtendedAudioPlaylist.Type.ALBUM,
			"https://gaana.com/album/" + seokey,
			getFirstText(json, "artworkUrl", "artwork"),
			getFirstTrackAuthor(tracks),
			tracks.size()
		);
	}

	private AudioItem getPlaylist(String seokey) throws IOException {
		JsonBrowser json = getJson(API_BASE + "/playlists?seokey=" + URLEncoder.encode(seokey, StandardCharsets.UTF_8));
		JsonBrowser playlist = json != null && !json.get("playlist").isNull() ? json.get("playlist") : json;
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
		JsonBrowser json = getJson(API_BASE + "/artists?seokey=" + URLEncoder.encode(seokey, StandardCharsets.UTF_8));
		if (json == null || json.isNull()) {
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = parseTrackList(json.get("top_tracks"));
		if (tracks.size() > ARTIST_TRACK_LIMIT) {
			tracks = new ArrayList<>(tracks.subList(0, ARTIST_TRACK_LIMIT));
		}
		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new ExtendedAudioPlaylist(
			getFirstText(json, "name", "title"),
			tracks,
			ExtendedAudioPlaylist.Type.ARTIST,
			getFirstText(json, "artist_url"),
			getFirstText(json, "artwork"),
			getFirstText(json, "name"),
			tracks.size()
		);
	}

	HttpGet createRequest(String url) {
		HttpGet request = new HttpGet(url);
		request.setHeader("User-Agent", USER_AGENT);
		request.setHeader("Accept", "*/*");
		request.setHeader("Origin", "https://gaana.com");
		request.setHeader("Referer", "https://gaana.com/");
		return request;
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
			log.error("Failed to close Gaana HTTP interface manager", e);
		}
	}

	private JsonBrowser getJson(String url) throws IOException {
		return getJson(this.httpInterfaceManager.getInterface(), url);
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

		String title = getFirstText(track, "title");
		String identifier = getFirstText(track, "track_id", "seokey");
		long duration = track.get("duration").asLong(0L) * 1000L;

		if (title == null || identifier == null || duration <= 0L) {
			return null;
		}

		String author = getFirstText(track, "artists", "artist");
		String songUrl = getFirstText(track, "song_url");
		String artworkUrl = getFirstText(track, "artworkUrl", "artwork", "artwork_url");
		String albumName = getFirstText(track, "album");
		String albumUrl = getFirstText(track, "album_url");
		String artistUrl = buildArtistUrl(track);
		String artistArtworkUrl = getFirstText(track, "artworkUrl", "artwork");
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

		return new GaanaAudioTrack(info, albumName, albumUrl, artistUrl, artistArtworkUrl, this);
	}

	private String buildArtistUrl(JsonBrowser track) {
		String seokeys = getFirstText(track, "artist_seokeys");
		if (seokeys == null || seokeys.isEmpty()) {
			return null;
		}
		String first = seokeys.split(",")[0].trim();
		if (first.isEmpty()) {
			return null;
		}
		return "https://gaana.com/artist/" + first;
	}

	private String getFirstTrackAuthor(List<AudioTrack> tracks) {
		if (tracks.isEmpty()) {
			return null;
		}
		return tracks.get(0).getInfo().author;
	}

	private JsonBrowser extractList(JsonBrowser json) {
		if (json == null || json.isNull()) {
			return null;
		}
		if (!json.get("data").isNull()) {
			return json.get("data");
		}
		return json;
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
		return text != null && !text.isEmpty() ? text : null;
	}

}