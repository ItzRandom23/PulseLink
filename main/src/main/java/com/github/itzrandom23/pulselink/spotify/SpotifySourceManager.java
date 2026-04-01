package com.github.itzrandom23.pulselink.spotify;

import com.github.topi314.lavasearch.AudioSearchManager;
import com.github.topi314.lavasearch.result.AudioSearchResult;
import com.github.topi314.lavasearch.result.BasicAudioSearchResult;
import com.github.itzrandom23.pulselink.PulseLinkTools;
import com.github.itzrandom23.pulselink.mirror.DefaultMirroringAudioTrackResolver;
import com.github.itzrandom23.pulselink.mirror.MirroringAudioSourceManager;
import com.github.itzrandom23.pulselink.mirror.MirroringAudioTrackResolver;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.util.stream.Collectors;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

public class SpotifySourceManager extends MirroringAudioSourceManager
        implements HttpConfigurable, AudioSearchManager {

    public static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://)(www\\.)?open\\.spotify\\.com/((?<region>[a-zA-Z-]+)/)?(?<type>track|album|playlist|artist)/(?<identifier>[a-zA-Z0-9-_]+)");
    public static final Pattern RADIO_MIX_QUERY_PATTERN = Pattern
            .compile("mix:(?<seedType>album|artist|track|isrc):(?<seed>[a-zA-Z0-9-_]+)");
    public static final String SEARCH_PREFIX = "spsearch:";
    public static final String RECOMMENDATIONS_PREFIX = "sprec:";
    public static final String PREVIEW_PREFIX = "spprev:";
    public static final long PREVIEW_LENGTH = 30000;
    public static final String SHARE_URL = "https://spotify.link/";
    public static final int PLAYLIST_MAX_PAGE_ITEMS = 100;
    public static final int ALBUM_MAX_PAGE_ITEMS = 50;
    private static final String DEFAULT_RESOLVER = "http://us2.leonodes.xyz:15410";
    public static final Set<AudioSearchResult.Type> SEARCH_TYPES = Set.of(AudioSearchResult.Type.ALBUM,
            AudioSearchResult.Type.ARTIST, AudioSearchResult.Type.PLAYLIST, AudioSearchResult.Type.TRACK);
    private static final Logger log = LoggerFactory.getLogger(SpotifySourceManager.class);

    private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
    private final String countryCode;
    private String resolver = DEFAULT_RESOLVER;
    private int playlistPageLimit = 6;
    private int albumPageLimit = 6;
    private boolean localFiles;
    private boolean resolveArtistsInSearch = true;

    public SpotifySourceManager(String[] providers, String countryCode, AudioPlayerManager audioPlayerManager) {
        this(countryCode, null, unused -> audioPlayerManager,
                new DefaultMirroringAudioTrackResolver(providers));
    }

    public SpotifySourceManager(String[] providers, String countryCode,
            Function<Void, AudioPlayerManager> audioPlayerManager) {
        this(countryCode, null, audioPlayerManager,
                new DefaultMirroringAudioTrackResolver(providers));
    }

    public SpotifySourceManager(String countryCode,
            AudioPlayerManager audioPlayerManager,
            MirroringAudioTrackResolver mirroringAudioTrackResolver) {
        this(countryCode, null, unused -> audioPlayerManager,
                mirroringAudioTrackResolver);
    }

    public SpotifySourceManager(String countryCode,
            Function<Void, AudioPlayerManager> audioPlayerManager,
            MirroringAudioTrackResolver mirroringAudioTrackResolver) {
        this(countryCode, null, audioPlayerManager, mirroringAudioTrackResolver);
    }

    public SpotifySourceManager(String countryCode,
            String apiUrl,
            Function<Void, AudioPlayerManager> audioPlayerManager,
            MirroringAudioTrackResolver mirroringAudioTrackResolver) {

        super(audioPlayerManager, mirroringAudioTrackResolver);

        if (countryCode == null || countryCode.isEmpty()) {
            countryCode = "US";
        }

        this.countryCode = countryCode;
        this.setApiUrl(apiUrl);

        this.httpInterfaceManager.configureRequests(config -> RequestConfig.copy(config)
                .setConnectTimeout(10000)
                .setConnectionRequestTimeout(10000)
                .setSocketTimeout(15000)
                .build());
    }

    public void setPlaylistPageLimit(int playlistPageLimit) {
        this.playlistPageLimit = playlistPageLimit;
    }

    public void setAlbumPageLimit(int albumPageLimit) {
        this.albumPageLimit = albumPageLimit;
    }

    public void setLocalFiles(boolean localFiles) {
        this.localFiles = localFiles;
    }

    public void setResolveArtistsInSearch(boolean resolveArtistsInSearch) {
        this.resolveArtistsInSearch = resolveArtistsInSearch;
    }

    public void setApiUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            this.resolver = DEFAULT_RESOLVER;
            return;
        }

        this.resolver = apiUrl;
    }

    @NotNull
    @Override
    public String getSourceName() {
        return "spotify";
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        var extendedAudioTrackInfo = super.decodeTrack(input);
        return new SpotifyAudioTrack(trackInfo,
                extendedAudioTrackInfo.albumName,
                extendedAudioTrackInfo.albumUrl,
                extendedAudioTrackInfo.artistUrl,
                extendedAudioTrackInfo.artistArtworkUrl,
                extendedAudioTrackInfo.previewUrl,
                extendedAudioTrackInfo.isPreview,
                this);
    }

    @Override
    @Nullable
    public AudioSearchResult loadSearch(@NotNull String query,
            @NotNull Set<AudioSearchResult.Type> types) {
        try {
            if (!query.startsWith(SEARCH_PREFIX)) {
                return null;
            }

            var q = URLEncoder.encode(
                    query.substring(SEARCH_PREFIX.length()),
                    StandardCharsets.UTF_8);

            var request = new HttpGet(
                    this.resolver + "/api/search?q=" + q);

            var json = PulseLinkTools.fetchResponseAsJson(
                    this.httpInterfaceManager.getInterface(),
                    request);

            var items = json.get("items");

            if (items == null || items.values().isEmpty()) {
                return null;
            }

            var tracks = new ArrayList<AudioTrack>();

            for (var item : items.values()) {

                var trackId = item.get("id").text();
                var artwork = item.get("artwork").safeText();
                var isrc = item.get("isrc").safeText();

                var info = new AudioTrackInfo(
                        item.get("title").text(),
                        parseArtists(item),
                        item.get("duration").asLong(0),
                        trackId,
                        false,
                        "https://open.spotify.com/track/" + trackId,
                        artwork,
                        isrc);

                tracks.add(new SpotifyAudioTrack(
                        info,
                        null,
                        null,
                        null,
                        artwork,
                        null,
                        false,
                        this));
            }

            return new BasicAudioSearchResult(
                    tracks,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>());

        } catch (Exception e) {
            log.error("Search failed", e);
            return null;
        }
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        var identifier = reference.identifier;
        var preview = reference.identifier.startsWith(PREVIEW_PREFIX);
        return this.loadItem(preview ? identifier.substring(PREVIEW_PREFIX.length()) : identifier, preview);
    }

    public AudioItem loadItem(String identifier, boolean preview) {
        try {

            // --- Handle Spotify search ---
            if (identifier.startsWith(SEARCH_PREFIX)) {
                var query = identifier.substring(SEARCH_PREFIX.length());

                var encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);

                var request = new HttpGet(
                        this.resolver + "/api/search?q=" + encoded);

                var json = PulseLinkTools.fetchResponseAsJson(
                        this.httpInterfaceManager.getInterface(),
                        request);

                var items = json.get("items");

                if (items == null || items.values().isEmpty()) {
                    return AudioReference.NO_TRACK;
                }

                var item = items.index(0);

                var trackId = item.get("id").text();
                var artwork = item.get("artwork").safeText();
                var isrc = item.get("isrc").safeText();

                var info = new AudioTrackInfo(
                        item.get("title").text(),
                        parseArtists(item),
                        item.get("duration").asLong(0),
                        trackId,
                        false,
                        "https://open.spotify.com/track/" + trackId,
                        artwork,
                        isrc);

                return new SpotifyAudioTrack(
                        info,
                        null,
                        null,
                        null,
                        artwork,
                        null,
                        false,
                        this);
            }

            var matcher = URL_PATTERN.matcher(identifier);

            if (matcher.find()) {
                var type = matcher.group("type");
                var id = matcher.group("identifier");

                if ("artist".equals(type)) {
                    return resolveArtist(id);
                }

                // track / album / playlist
                return resolveViaLeo(identifier, preview);
            }

            if (identifier.startsWith(RECOMMENDATIONS_PREFIX)) {
                return resolveRecommendations(
                        identifier.substring(RECOMMENDATIONS_PREFIX.length()));
            }

            return null;

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String parseArtists(JsonBrowser item) {
        return item.get("artists").isList()
                ? String.join(", ",
                        item.get("artists").values().stream()
                                .map(v -> v.text())
                                .collect(Collectors.toList()))
                : item.get("artists").text();
    }

    private AudioItem parsePlaylist(JsonBrowser json, String fallbackName) {

        if (json == null || json.get("items") == null || json.get("items").values().isEmpty()) {
            return null;
        }

        var tracks = new ArrayList<AudioTrack>();

        for (var item : json.get("items").values()) {

            var trackId = item.get("id").text();
            var artwork = item.get("artwork").safeText();
            var isrc = item.get("isrc").safeText();

            var info = new AudioTrackInfo(
                    item.get("title").text(),
                    parseArtists(item),
                    item.get("duration").asLong(0),
                    trackId,
                    false,
                    "https://open.spotify.com/track/" + trackId,
                    artwork, // <-- artworkUrl field
                    isrc // <-- ISRC field
            );

            tracks.add(new SpotifyAudioTrack(
                    info,
                    null,
                    null,
                    null,
                    artwork,
                    null,
                    false,
                    this));
        }

        if (tracks.size() == 1) {
            return tracks.get(0);
        }

        return new BasicAudioPlaylist(
                json.get("name").textOrDefault(fallbackName),
                tracks,
                null,
                false);
    }

    private AudioItem resolveViaLeo(String identifier, boolean preview) throws IOException {

        if (!identifier.contains("spotify.com")) {
            return null;
        }

        var encoded = URLEncoder.encode(identifier, StandardCharsets.UTF_8);

        var request = new HttpGet(
                this.resolver + "/api/resolve?url=" + encoded);

        var json = PulseLinkTools.fetchResponseAsJson(
                this.httpInterfaceManager.getInterface(),
                request);

        return parsePlaylist(json, "Spotify");
    }

    private AudioItem resolveRecommendations(String identifier) throws IOException {

        var encoded = URLEncoder.encode(identifier, StandardCharsets.UTF_8);

        var request = new HttpGet(
                this.resolver + "/api/recommendations?url=" + encoded);

        var json = PulseLinkTools.fetchResponseAsJson(
                this.httpInterfaceManager.getInterface(),
                request);

        return parsePlaylist(json, "Recommendations");
    }

    private AudioItem resolveArtist(String id) throws IOException {

        var request = new HttpGet(
                this.resolver + "/api/artist-top-tracks?id=" + id);

        var json = PulseLinkTools.fetchResponseAsJson(
                this.httpInterfaceManager.getInterface(),
                request);

        var artistName = json.get("name").safeText();

        if (artistName != null) {
            json.put("name", artistName + " - Top Tracks");
        }

        return parsePlaylist(json, "Artist Top Tracks");
    }

    @Override
    public void shutdown() {
        try {
            this.httpInterfaceManager.close();
        } catch (IOException e) {
            log.error("Failed to close HTTP interface manager", e);
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
}
