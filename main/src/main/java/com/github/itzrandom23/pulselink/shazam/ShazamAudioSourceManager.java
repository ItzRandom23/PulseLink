package com.github.itzrandom23.pulselink.shazam;

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
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShazamAudioSourceManager extends MirroringAudioSourceManager implements HttpConfigurable, AudioSearchManager {

	private static final Logger log = LoggerFactory.getLogger(ShazamAudioSourceManager.class);

	private static final String SEARCH_PREFIX = "szsearch:";
	private static final String SEARCH_API_BASE = "https://www.shazam.com/services/amapi/v1/catalog/US/search?types=songs&term=%s&limit=%d";
	private static final Pattern URL_PATTERN = Pattern.compile("https?://(?:www\\.)?shazam\\.com/song/\\d+(?:/[^/?#]+)?", Pattern.CASE_INSENSITIVE);
	private static final Pattern ISRC_PATTERN = Pattern.compile("(?:\"isrc\"|\\\\\"isrc\\\\\")\\s*:\\s*\\\\?\"([A-Z]{2}[A-Z0-9]{3}\\d{7})\\\\?\"");
	private static final Pattern DURATION_PATTERN = Pattern.compile("(?:\"duration\"|\\\\\"duration\\\\\")\\s*:\\s*\\\\?\"(PT[^\"]+)\\\\?\"");
	private static final Pattern OG_TITLE_PATTERN = Pattern.compile("^(.+?) - (.+?)(?::|$)");
	private static final Pattern ISO_DURATION_PATTERN = Pattern.compile("^PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?$");
	private static final int SEARCH_LIMIT = 10;

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
		if (types.isEmpty()) {
			types = Set.of(AudioSearchResult.Type.TRACK);
		}
		if (!types.contains(AudioSearchResult.Type.TRACK)) {
			return null;
		}
		if (!query.startsWith(SEARCH_PREFIX)) {
			return null;
		}

		String actualQuery = query.substring(SEARCH_PREFIX.length());
		try {
			AudioItem item = getSearch(actualQuery);
			if (item instanceof BasicAudioPlaylist playlist) {
				return new BasicAudioSearchResult(playlist.getTracks(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to search Shazam", e);
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
			if (!URL_PATTERN.matcher(identifier).matches()) {
				return null;
			}

			AudioTrack track = resolveTrack(identifier);
			return track != null ? track : AudioReference.NO_TRACK;
		} catch (IOException e) {
			throw new RuntimeException("Failed to load Shazam item", e);
		}
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
		var extended = super.decodeTrack(input);
		return new ShazamAudioTrack(
			trackInfo,
			extended.albumName,
			extended.albumUrl,
			extended.artistUrl,
			extended.artistArtworkUrl,
			this
		);
	}

	private AudioItem getSearch(String query) throws IOException {
		String url = String.format(SEARCH_API_BASE, URLEncoder.encode(query, StandardCharsets.UTF_8), SEARCH_LIMIT);
		JsonBrowser json = getJson(url);
		JsonBrowser songs = json != null ? json.get("results").get("songs").get("data") : null;
		if (songs == null || songs.isNull() || songs.values().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		List<AudioTrack> tracks = new ArrayList<>();
		for (JsonBrowser song : songs.values()) {
			AudioTrack track = buildTrack(song);
			if (track != null) {
				tracks.add(track);
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}
		return new BasicAudioPlaylist("Shazam Search: " + query, tracks, null, true);
	}

	private AudioTrack resolveTrack(String url) throws IOException {
		String html = fetchText(url);
		if (html == null || html.isEmpty()) {
			return null;
		}

		Document document = Jsoup.parse(html, url);
		String title = textOrNull(document.selectFirst("meta[property=og:title]"), "content");
		String artworkUrl = textOrNull(document.selectFirst("meta[property=og:image]"), "content");

		String artist = null;
		if (title != null) {
			Matcher ogMatch = OG_TITLE_PATTERN.matcher(title);
			if (ogMatch.find()) {
				title = ogMatch.group(1).trim();
				artist = ogMatch.group(2).trim();
			}
		}

		String htmlTitle = textOrNull(document.selectFirst("h1"), null);
		if ((title == null || title.isBlank()) && htmlTitle != null && !htmlTitle.isBlank()) {
			title = htmlTitle.trim();
		}

		String htmlArtist = textOrNull(document.selectFirst("a[href*=/artist/]"), null);
		if (artist == null || artist.isBlank()) {
			artist = htmlArtist;
		}

		if (title == null || title.isBlank()) {
			title = "Unknown";
		}
		if (artist == null || artist.isBlank()) {
			artist = "Unknown";
		}

		String identifier = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
		identifier = identifier.substring(identifier.lastIndexOf('/') + 1);
		String isrc = firstMatch(ISRC_PATTERN, html);
		long duration = parseIsoDuration(firstMatch(DURATION_PATTERN, html));

		AudioTrackInfo info = new AudioTrackInfo(
			title,
			artist,
			duration,
			identifier,
			false,
			url,
			artworkUrl,
			isrc
		);

		return new ShazamAudioTrack(info, null, null, null, null, this);
	}

	private AudioTrack buildTrack(JsonBrowser item) {
		if (item == null || item.isNull() || item.get("id").isNull()) {
			return null;
		}

		JsonBrowser attributes = item.get("attributes");
		String artworkUrl = null;
		JsonBrowser artwork = attributes.get("artwork");
		if (!artwork.isNull() && !artwork.get("url").isNull()) {
			artworkUrl = artwork.get("url").text()
				.replace("{w}", artwork.get("width").text())
				.replace("{h}", artwork.get("height").text());
		}

		String url = attributes.get("url").text();
		String title = attributes.get("name").text();
		String artist = attributes.get("artistName").text();
		long duration = attributes.get("durationInMillis").asLong(0L);
		String isrc = attributes.get("isrc").isNull() ? null : attributes.get("isrc").text();

		AudioTrackInfo info = new AudioTrackInfo(
			title != null ? title : "Unknown",
			artist != null ? artist : "Unknown",
			duration,
			item.get("id").text(),
			false,
			url,
			artworkUrl,
			isrc
		);

		return new ShazamAudioTrack(info, null, null, null, null, this);
	}

	private JsonBrowser getJson(String url) throws IOException {
		HttpGet request = new HttpGet(url);
		request.setHeader("Accept", "application/json");
		request.setHeader("User-Agent", "Mozilla/5.0");
		return PulseLinkTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
	}

	private String fetchText(String url) throws IOException {
		HttpGet request = new HttpGet(url);
		request.setHeader("User-Agent", "Mozilla/5.0");
		try (var response = this.httpInterfaceManager.getInterface().execute(request)) {
			return IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
		}
	}

	private String textOrNull(org.jsoup.nodes.Element element, String attribute) {
		if (element == null) {
			return null;
		}
		if (attribute == null) {
			String text = element.text();
			return text == null || text.isBlank() ? null : text;
		}
		String value = element.attr(attribute);
		return value == null || value.isBlank() ? null : value;
	}

	private String firstMatch(Pattern pattern, String input) {
		Matcher matcher = pattern.matcher(input);
		return matcher.find() ? matcher.group(1) : null;
	}

	private long parseIsoDuration(String isoDuration) {
		if (isoDuration == null || isoDuration.isBlank()) {
			return 0L;
		}
		Matcher matcher = ISO_DURATION_PATTERN.matcher(isoDuration);
		if (!matcher.matches()) {
			return 0L;
		}

		double seconds = 0D;
		if (matcher.group(1) != null) {
			seconds += Long.parseLong(matcher.group(1)) * 3600D;
		}
		if (matcher.group(2) != null) {
			seconds += Long.parseLong(matcher.group(2)) * 60D;
		}
		if (matcher.group(3) != null) {
			seconds += Double.parseDouble(matcher.group(3));
		}
		return Math.round(seconds * 1000D);
	}
}
