package com.github.itzrandom23.pulselink.shazam;

import com.github.itzrandom23.pulselink.PulseLinkTools;
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
import java.net.URI;
import java.net.URLDecoder;
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
	private static final String ALT_SEARCH_PREFIX = "shsearch:";
	private static final String SEARCH_API_BASE = "https://www.shazam.com/services/amapi/v1/catalog/US/search?types=songs&term=%s&limit=%d";
	private static final Pattern URL_PATTERN = Pattern.compile("https?://(?:www\\.)?shazam\\.com/(?:song|track)/(\\d+)(?:/[^/?#]+)?/?(?:[?#].*)?", Pattern.CASE_INSENSITIVE);
	private static final Pattern SLUG_PATTERN = Pattern.compile("https?://(?:www\\.)?shazam\\.com/(?:song|track)/\\d+/([^/?#]+)/?(?:[?#].*)?", Pattern.CASE_INSENSITIVE);
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
		if (!isSearchQuery(query)) {
			return null;
		}

		String actualQuery = stripSearchPrefix(query);
		try {
			AudioItem item = getSearch(actualQuery);
			if (item instanceof BasicAudioPlaylist playlist) {
				return new BasicAudioSearchResult(playlist.getTracks(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
			}
		} catch (FriendlyException | IOException e) {
			log.warn("Failed to search Shazam for query {}", actualQuery, e);
		}

		return new BasicAudioSearchResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
		try {
			String identifier = reference.identifier;
			if (isSearchQuery(identifier)) {
				return getSearch(stripSearchPrefix(identifier));
			}
			if (!URL_PATTERN.matcher(identifier).matches()) {
				return null;
			}

			AudioTrack track = resolveTrack(identifier);
			return track != null ? track : AudioReference.NO_TRACK;
		} catch (FriendlyException | IOException e) {
			log.warn("Failed to load Shazam item {}", reference.identifier, e);
			return AudioReference.NO_TRACK;
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
		url = url.replace("/track/", "/song/");
        String html = fetchText(url); 
		if (html == null || html.isEmpty()) {
			return resolveTrackFromSlug(url);
		}

		Document document = Jsoup.parse(html, url);
		String title = textByClassPart(document, html, "NewTrackPageHeader_trackTitle__");
		String artist = textByClassPart(document, html, "TrackPageArtistLink_artistNameText__");
		String artworkUrl = textOrNull(document.selectFirst("meta[property=og:image]"), "content");
		if (artworkUrl == null || artworkUrl.isBlank()) {
			artworkUrl = extractArtworkFromImgAlt(document);
		}

		String ogTitle = textOrNull(document.selectFirst("meta[property=og:title]"), "content");
		if (ogTitle != null) {
			Matcher ogMatch = OG_TITLE_PATTERN.matcher(ogTitle);
			if (ogMatch.find()) {
				if (title == null || title.isBlank()) {
					title = ogMatch.group(1).trim();
				}
				if (artist == null || artist.isBlank()) {
					artist = ogMatch.group(2).trim();
				}
			} else if (title == null || title.isBlank()) {
				title = ogTitle;
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
			return resolveTrackFromSlug(url);
		}
		if (artist == null || artist.isBlank()) {
			artist = "Unknown";
		}

		String identifier = extractIdentifier(url);
		if (identifier == null) {
			return null;
		}
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

	private AudioTrack resolveTrackFromSlug(String url) throws IOException {
		String query = extractSlugQuery(url);
		if (query == null || query.isBlank()) {
			return null;
		}
		String identifier = extractIdentifier(url);

		try {
			AudioItem item = getSearch(query);
			if (item instanceof BasicAudioPlaylist playlist && !playlist.getTracks().isEmpty()) {
				if (identifier != null) {
					for (AudioTrack track : playlist.getTracks()) {
						if (identifier.equals(track.getInfo().identifier)) {
							log.debug("Resolved Shazam URL {} through slug search fallback '{}' using matching id {}.", url, query, identifier);
							return track;
						}
					}
				}
				log.debug("Resolved Shazam URL {} through slug search fallback '{}'.", url, query);
				return playlist.getTracks().get(0);
			}
			if (item instanceof AudioTrack track) {
				return track;
			}
		} catch (FriendlyException e) {
			log.warn("Shazam slug search fallback failed for query {}", query, e);
		}

		return null;
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
		setBrowserHeaders(request, "application/json");
		return PulseLinkTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
	}

	private String fetchText(String url) throws IOException {
		return fetchText(url, 0);
	}

	private String fetchText(String url, int redirectCount) throws IOException {
		if (redirectCount > 5) {
			log.warn("Too many redirects while loading Shazam URL {}", url);
			return null;
		}

		HttpGet request = new HttpGet(url);
		setBrowserHeaders(request, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
		try (var response = this.httpInterfaceManager.getInterface().execute(request)) {
			int statusCode = response.getStatusLine().getStatusCode();
			if (statusCode >= 300 && statusCode < 400) {
				var location = response.getFirstHeader("Location");
				if (location == null || location.getValue() == null || location.getValue().isBlank()) {
					log.warn("Shazam returned redirect without a Location header for {}", url);
					return null;
				}
				String redirectUrl = URI.create(url).resolve(location.getValue()).toString();
				return fetchText(redirectUrl, redirectCount + 1);
			}
			if (statusCode >= 400) {
				log.warn("Shazam returned HTTP {} while loading {}", statusCode, url);
				return null;
			}
			if (response.getEntity() == null) {
				return null;
			}
			return IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
		}
	}

	private void setBrowserHeaders(HttpGet request, String accept) {
		request.setHeader("Accept", accept);
		request.setHeader("Accept-Language", "en-US,en;q=0.9");
		request.setHeader("Cache-Control", "max-age=0");
		request.setHeader("Cookie", "geoip_country=IN; _bszm=1");
		request.setHeader("Sec-CH-UA", "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Brave\";v=\"150\"");
		request.setHeader("Sec-CH-UA-Mobile", "?0");
		request.setHeader("Sec-CH-UA-Platform", "\"Windows\"");
		request.setHeader("Referer", "https://www.shazam.com/");
		request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36");
		if (accept.startsWith("text/html")) {
			request.setHeader("Sec-Fetch-Dest", "document");
			request.setHeader("Sec-Fetch-Mode", "navigate");
			request.setHeader("Sec-Fetch-Site", "same-origin");
			request.setHeader("Sec-Fetch-User", "?1");
			request.setHeader("Upgrade-Insecure-Requests", "1");
		}
	}

	private boolean isSearchQuery(String query) {
		return query.startsWith(SEARCH_PREFIX) || query.startsWith(ALT_SEARCH_PREFIX);
	}

	private String stripSearchPrefix(String query) {
		if (query.startsWith(SEARCH_PREFIX)) {
			return query.substring(SEARCH_PREFIX.length());
		}
		return query.substring(ALT_SEARCH_PREFIX.length());
	}

	private String extractIdentifier(String url) {
		Matcher matcher = URL_PATTERN.matcher(url);
		return matcher.matches() ? matcher.group(1) : null;
	}

	private String extractSlugQuery(String url) {
		Matcher matcher = SLUG_PATTERN.matcher(url);
		if (!matcher.matches()) {
			return null;
		}
		return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8).replace('-', ' ').replace('_', ' ').trim();
	}

	private String textByClassPart(Document document, String html, String classPart) {
		String selectorText = textOrNull(document.selectFirst("[class*=\"" + classPart + "\"]"), null);
		if (selectorText != null && !selectorText.isBlank()) {
			return selectorText.trim();
		}
		return extractTextAfterClass(html, classPart);
	}

	private String extractTextAfterClass(String html, String classPart) {
		int from = 0;
		while (true) {
			int classIndex = html.indexOf("class=\"", from);
			if (classIndex < 0) {
				return null;
			}

			int quoteIndex = html.indexOf('"', classIndex + 7);
			if (quoteIndex < 0) {
				return null;
			}

			String classValue = html.substring(classIndex + 7, quoteIndex);
			if (classValue.contains(classPart)) {
				int start = html.indexOf('>', quoteIndex);
				if (start < 0) {
					return null;
				}
				int end = html.indexOf('<', start + 1);
				if (end < 0) {
					return null;
				}
				String text = Jsoup.parse(html.substring(start + 1, end)).text().trim();
				return text.isBlank() ? null : text;
			}
			from = quoteIndex + 1;
		}
	}

	private String extractArtworkFromImgAlt(Document document) {
		var image = document.selectFirst("img[alt=\"album cover\"], img[alt=\"song thumbnail\"]");
		if (image == null) {
			return null;
		}

		String srcset = image.attr("srcset");
		if (srcset != null && !srcset.isBlank()) {
			int separator = srcset.indexOf(' ');
			return separator < 0 ? srcset : srcset.substring(0, separator);
		}

		String src = image.attr("src");
		return src == null || src.isBlank() ? null : src;
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
