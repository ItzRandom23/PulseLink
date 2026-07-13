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
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import org.apache.http.client.config.RequestConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Resolves Shazam URLs through its public Apple Music catalog search API. */
public class ShazamAudioSourceManager extends MirroringAudioSourceManager implements HttpConfigurable, AudioSearchManager {

	private static final Logger log = LoggerFactory.getLogger(ShazamAudioSourceManager.class);
	private static final String SEARCH_PREFIX = "szsearch:";
	private static final String ALT_SEARCH_PREFIX = "shsearch:";
	private static final String CATALOG_SEARCH_API = "https://www.shazam.com/services/amapi/v1/catalog/US/search?types=%s&term=%s&limit=%d";
	/* Shazam's Fastly edge may reject this endpoint over Apache HttpClient's HTTP/1.1. */
	private static final HttpClient CATALOG_HTTP_CLIENT = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_2)
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	public ShazamAudioSourceManager(String[] providers, AudioPlayerManager audioPlayerManager) {
		this(providers, unused -> audioPlayerManager);
	}

	public ShazamAudioSourceManager(String[] providers, Function<Void, AudioPlayerManager> audioPlayerManager) {
		this(audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers));
	}

	public ShazamAudioSourceManager(Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver resolver) {
		super(audioPlayerManager, resolver);
		this.httpInterfaceManager.configureRequests(config -> RequestConfig.copy(config)
			.setConnectTimeout(10000).setConnectionRequestTimeout(10000).setSocketTimeout(15000).build());
	}

	@NotNull @Override public String getSourceName() { return "shazam"; }

	@Override
	public @Nullable AudioSearchResult loadSearch(@NotNull String query, @NotNull Set<AudioSearchResult.Type> types) {
		if ((!types.isEmpty() && !types.contains(AudioSearchResult.Type.TRACK)) || !isSearchQuery(query)) return null;
		try {
			AudioItem item = asShazamItem(findCatalogItem("songs", stripSearchPrefix(query), null, 1), null);
			return toSearchResult(item);
		} catch (IOException e) {
			log.warn("Failed to search Shazam for query {}", stripSearchPrefix(query), e);
			return emptySearchResult();
		}
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
		try {
			if (isSearchQuery(reference.identifier)) return asShazamItem(findCatalogItem("songs", stripSearchPrefix(reference.identifier), null, 1), null);
			ShazamUrl page = ShazamUrl.parse(reference.identifier);
			if (page == null || page.route == ShazamUrl.Route.UNSUPPORTED) return null;
			if (page.route == ShazamUrl.Route.TRACK) return AudioReference.NO_TRACK;
			String type = switch (page.route) {
				case SONG -> "songs";
				case ARTIST -> "artists";
				case ALBUM -> "albums";
				default -> null;
			};
			if (type == null) return AudioReference.NO_TRACK;
			AudioItem item = asShazamItem(findCatalogItem(type, catalogTerm(page.slug), page.id, 5), page.originalUrl);
			if (page.route == ShazamUrl.Route.ARTIST && item instanceof AudioPlaylist playlist) {
				return new BasicAudioPlaylist(displayName(page.slug) + " - Top Tracks", playlist.getTracks(), playlist.getSelectedTrack(), false);
			}
			return item;
		} catch (IOException | FriendlyException e) {
			log.warn("Failed to resolve Shazam URL {}", reference.identifier, e);
			return AudioReference.NO_TRACK;
		}
	}

	@Override public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) { return new ShazamAudioTrack(trackInfo, this); }

	private AudioItem asShazamItem(AudioItem item, @Nullable String shazamUrl) {
		if (item instanceof AudioTrack track) return asShazamTrack(track, shazamUrl);
		if (item instanceof AudioPlaylist playlist) {
			List<AudioTrack> tracks = playlist.getTracks().stream().map(track -> asShazamTrack(track, shazamUrl)).toList();
			return tracks.isEmpty() ? AudioReference.NO_TRACK : new BasicAudioPlaylist(playlist.getName(), tracks, null, playlist.isSearchResult());
		}
		return item;
	}

	private AudioTrack asShazamTrack(AudioTrack track, @Nullable String shazamUrl) {
		AudioTrackInfo info = track.getInfo();
		String uri = shazamUrl == null || !shazamUrl.contains("/song/") ? generatedSongUrl(info) : shazamUrl;
		return new ShazamAudioTrack(new AudioTrackInfo(info.title, info.author, info.length, info.identifier, info.isStream, uri, info.artworkUrl, info.isrc), this);
	}

	private String generatedSongUrl(AudioTrackInfo info) {
		String slug = info.title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-|-$", "");
		return "https://www.shazam.com/song/" + info.identifier + "/" + (slug.isBlank() ? "track" : slug);
	}

	private AudioItem findCatalogItem(String type, String term, @Nullable String expectedId, int limit) throws IOException {
		if (term == null || term.isBlank()) return AudioReference.NO_TRACK;
		String url = String.format(CATALOG_SEARCH_API, type, URLEncoder.encode(term, StandardCharsets.UTF_8), limit);
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(15))
			.header("Accept", "application/json")
			.header("Accept-Language", "en-US,en;q=0.9")
			// Shazam accepts the same request from curl on affected VPS hosts, but
			// rejects a Chrome-looking Java TLS/header combination with HTTP 405.
			.header("User-Agent", "curl/8.5.0")
			.GET().build();
		HttpResponse<String> response;
		try {
			response = CATALOG_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while requesting Shazam's catalog API", e);
		}
		if (response.statusCode() != 200) {
			log.warn("Shazam catalog API returned HTTP {} for {}.", response.statusCode(), url);
			return AudioReference.NO_TRACK;
		}
		JsonBrowser items = JsonBrowser.parse(response.body()).get("results").get(type).get("data");
		for (JsonBrowser item : items.values()) {
			if (expectedId != null && !expectedId.equals(item.get("id").safeText())) continue;
			String appleMusicUrl = item.get("attributes").get("url").safeText();
			if (!appleMusicUrl.isBlank()) {
				AudioItem resolved = loadAppleMusicUrl(appleMusicUrl);
				if (resolved == AudioReference.NO_TRACK) log.warn("Shazam matched {} ID {}, but the Apple Music source returned no track for {}", type, item.get("id").safeText(), appleMusicUrl);
				return resolved;
			}
		}
		return AudioReference.NO_TRACK;
	}

	private AudioItem loadAppleMusicUrl(String url) {
		CompletableFuture<AudioItem> result = new CompletableFuture<>();
		getAudioPlayerManager().loadItem(url, new AudioLoadResultHandler() {
			@Override public void trackLoaded(AudioTrack track) { result.complete(track); }
			@Override public void playlistLoaded(AudioPlaylist playlist) { result.complete(playlist); }
			@Override public void noMatches() { result.complete(AudioReference.NO_TRACK); }
			@Override public void loadFailed(FriendlyException exception) { result.completeExceptionally(exception); }
		});
		return result.join();
	}

	private AudioSearchResult toSearchResult(AudioItem item) {
		if (item instanceof AudioTrack track) return new BasicAudioSearchResult(List.of(track), List.of(), List.of(), List.of(), List.of());
		if (item instanceof AudioPlaylist playlist) return new BasicAudioSearchResult(playlist.getTracks(), List.of(), List.of(), List.of(), List.of());
		return emptySearchResult();
	}

	private AudioSearchResult emptySearchResult() { return new BasicAudioSearchResult(List.of(), List.of(), List.of(), List.of(), List.of()); }
	private boolean isSearchQuery(String query) { return query.startsWith(SEARCH_PREFIX) || query.startsWith(ALT_SEARCH_PREFIX); }
	private String stripSearchPrefix(String query) { return query.substring(query.startsWith(SEARCH_PREFIX) ? SEARCH_PREFIX.length() : ALT_SEARCH_PREFIX.length()); }
	private String catalogTerm(String slug) { return slug == null ? "" : slug.replace('-', ' ').replace('_', ' '); }
	private String displayName(String slug) {
		StringBuilder output = new StringBuilder();
		for (String word : slug.replace('_', '-').split("-")) {
			if (word.isBlank()) continue;
			if (!output.isEmpty()) output.append(' ');
			output.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return output.isEmpty() ? "Shazam Artist" : output.toString();
	}
}
