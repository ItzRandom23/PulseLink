package com.github.itzrandom23.pulselink.soundcloud;

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
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SoundCloudAudioSourceManager extends ExtendedAudioSourceManager implements HttpConfigurable, AudioSearchManager {

    private static final Logger log = LoggerFactory.getLogger(SoundCloudAudioSourceManager.class);

    private static final String BASE_URL = "https://api-v2.soundcloud.com";
    private static final String WEBSITE_URL = "https://soundcloud.com";
    private static final String SEARCH_PREFIX = "scsearch:";
    private static final Set<AudioSearchResult.Type> SEARCH_TYPES = Set.of(AudioSearchResult.Type.TRACK);
    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("(?:[?&/]?(?:client_id)[\\s:=&]*\"?|\"data\":\\{\"id\":\")([A-Za-z0-9]{32})\"?");
    private static final Pattern ASSET_PATTERN = Pattern.compile("https://a-v2\\.sndcdn\\.com/assets/[a-zA-Z0-9-]+\\.js");
    private static final Pattern SEARCH_URL_PATTERN = Pattern.compile("^https?://(?:www\\.)?soundcloud\\.com/search(?:/(sounds|people|albums|sets))?(?:\\?|$)", Pattern.CASE_INSENSITIVE);
    private static final int BATCH_SIZE = 50;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";

    private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();
    private final SoundCloudConfig config;
    private volatile String clientId;

    public SoundCloudAudioSourceManager(@NotNull SoundCloudConfig config) {
        this.config = config;
        this.clientId = config.getClientId();
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
        if (!types.contains(AudioSearchResult.Type.TRACK)) {
            return null;
        }
        if (query.startsWith(SEARCH_PREFIX)) {
            try {
                return getSearchResult(query.substring(SEARCH_PREFIX.length()));
            } catch (IOException e) {
                throw new RuntimeException("Failed to search SoundCloud", e);
            }
        }
        return null;
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        try {
            String identifier = reference.identifier;
            if (identifier.startsWith(SEARCH_PREFIX)) {
                return getSearch(identifier.substring(SEARCH_PREFIX.length()));
            }

            Matcher searchMatch = SEARCH_URL_PATTERN.matcher(identifier);
            if (searchMatch.find()) {
                return resolveSearchUrl(identifier);
            }

            if (!identifier.startsWith("http")) {
                return null;
            }

            URI uri = URI.create(identifier);
            if (uri.getHost() == null || !uri.getHost().contains("soundcloud.com")) {
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

    public HttpInterface getHttpInterface() {
        return this.httpInterfaceManager.getInterface();
    }

    public HttpGet createRequest(String url) {
        HttpGet request = new HttpGet(url);
        request.setHeader("User-Agent", USER_AGENT);
        request.setHeader("Accept", "application/json, text/plain, */*");
        request.setHeader("Referer", WEBSITE_URL + "/");
        request.setConfig(RequestConfig.custom()
            .setConnectTimeout(10000)
            .setConnectionRequestTimeout(10000)
            .setSocketTimeout(20000)
            .build());
        return request;
    }

    StreamInfo getStreamInfo(HttpInterface httpInterface, String trackId) throws IOException {
        ensureClientId(httpInterface);

        String url = BASE_URL + "/tracks/" + encode(trackId) + "?client_id=" + encode(this.clientId);
        JsonBrowser json = getJson(httpInterface, url);
        if (json == null || json.isNull()) {
            return null;
        }

        JsonBrowser media = json.get("media");
        if (media == null || media.isNull()) {
            return null;
        }
        List<JsonBrowser> transcodings = media.get("transcodings").values();
        if (transcodings.isEmpty()) {
            return null;
        }

        JsonBrowser selected = selectTranscoding(transcodings);
        if (selected == null || selected.isNull()) {
            return null;
        }

        String protocol = getText(selected.get("format"), "protocol");
        String mimeType = getText(selected.get("format"), "mime_type");
        String streamUrl = getText(selected, "url");
        if (streamUrl == null) {
            return null;
        }

        String resolved = resolveStreamUrl(httpInterface, streamUrl);
        if (resolved == null) {
            return null;
        }

        if (resolved.contains("cf-preview-media.sndcdn.com") || resolved.contains("/preview/")) {
            throw new IOException("SoundCloud stream is preview-only");
        }

        Protocol resolvedProtocol = "hls".equalsIgnoreCase(protocol) ? Protocol.HLS : Protocol.PROGRESSIVE;
        if (mimeType == null) {
            mimeType = "";
        }

        if (mimeType.contains("opus")) {
            log.warn("SoundCloud selected Opus stream which may not be supported (track: {})", trackId);
        }

        return new StreamInfo(resolved, resolvedProtocol, mimeType);
    }

    List<String> resolveHlsSegments(HttpInterface httpInterface, String masterUrl) throws IOException {
        String masterBody = fetchText(httpInterface, masterUrl);
        if (masterBody == null || masterBody.isBlank()) {
            return Collections.emptyList();
        }
        String mediaUrl = masterBody.contains("#EXT-X-STREAM-INF")
            ? firstMediaUrl(masterBody, masterUrl)
            : masterUrl;
        String mediaBody = mediaUrl.equals(masterUrl) ? masterBody : fetchText(httpInterface, mediaUrl);
        if (mediaBody == null || mediaBody.isBlank()) {
            return Collections.emptyList();
        }
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

    private AudioSearchResult getSearchResult(String query) throws IOException {
        var result = getSearch(query);
        if (result instanceof BasicAudioPlaylist playlist) {
            return new BasicAudioSearchResult(playlist.getTracks(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
        return new BasicAudioSearchResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    private AudioItem resolveUrl(String url) throws IOException {
        ensureClientId();
        String reqUrl = BASE_URL + "/resolve?" + buildParams(Map.of(
            "url", url,
            "client_id", this.clientId
        ));

        JsonBrowser body = getJson(reqUrl);
        if (body == null || body.isNull()) {
            return AudioReference.NO_TRACK;
        }

        String kind = getText(body, "kind");
        if ("track".equalsIgnoreCase(kind)) {
            AudioTrack track = buildTrack(body);
            return track != null ? track : AudioReference.NO_TRACK;
        }
        if ("playlist".equalsIgnoreCase(kind)) {
            return resolvePlaylist(body, url);
        }

        return AudioReference.NO_TRACK;
    }

    private AudioItem resolveSearchUrl(String url) throws IOException {
        try {
            URI uri = URI.create(url);
            String query = null;
            if (uri.getQuery() != null) {
                for (String part : uri.getQuery().split("&")) {
                    String[] kv = part.split("=", 2);
                    if (kv.length == 2 && "q".equals(kv[0])) {
                        query = decode(kv[1]);
                        break;
                    }
                }
            }
            if (query == null || query.isBlank()) {
                return AudioReference.NO_TRACK;
            }
            return getSearch(query);
        } catch (IllegalArgumentException e) {
            return AudioReference.NO_TRACK;
        }
    }

    private AudioItem getSearch(String query) throws IOException {
        if (query == null || query.isBlank()) {
            return AudioReference.NO_TRACK;
        }

        ensureClientId();
        int limit = Math.max(1, config.getSearchLimit());
        String url = BASE_URL + "/search/tracks?" + buildParams(Map.of(
            "q", query,
            "client_id", this.clientId,
            "limit", Integer.toString(limit),
            "offset", "0",
            "linked_partitioning", "1"
        ));

        JsonBrowser json = getJson(url);
        if (json == null || json.isNull()) {
            return AudioReference.NO_TRACK;
        }

        JsonBrowser collection = json.get("collection");
        List<AudioTrack> tracks = new ArrayList<>();
        for (JsonBrowser item : collection.values()) {
            AudioTrack track = buildTrack(item);
            if (track != null) {
                tracks.add(track);
            }
        }

        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }
        return new BasicAudioPlaylist("SoundCloud Search: " + query, tracks, null, true);
    }

    private AudioItem resolvePlaylist(JsonBrowser body, String url) throws IOException {
        List<JsonBrowser> complete = new ArrayList<>();
        List<String> ids = new ArrayList<>();

        for (JsonBrowser track : body.get("tracks").values()) {
            if (track == null || track.isNull()) {
                continue;
            }
            if (getText(track, "title") != null && !track.get("user").isNull()) {
                complete.add(track);
            } else {
                String id = getText(track, "id");
                if (id != null) {
                    ids.add(id);
                }
            }
        }

        int limit = Math.max(1, config.getPlaylistLoadLimit());
        int remaining = Math.max(0, limit - complete.size());
        if (!ids.isEmpty() && remaining > 0) {
            List<String> needed = ids.subList(0, Math.min(ids.size(), remaining));
            for (int i = 0; i < needed.size(); i += BATCH_SIZE) {
                List<String> chunk = needed.subList(i, Math.min(needed.size(), i + BATCH_SIZE));
                String batchUrl = BASE_URL + "/tracks?" + buildParams(Map.of(
                    "ids", String.join(",", chunk),
                    "client_id", this.clientId
                ));
                JsonBrowser batch = getJson(batchUrl);
                if (batch != null && batch.isList()) {
                    for (JsonBrowser item : batch.values()) {
                        complete.add(item);
                    }
                }
            }
        }

        List<AudioTrack> tracks = new ArrayList<>();
        for (JsonBrowser item : complete) {
            if (tracks.size() >= limit) {
                break;
            }
            AudioTrack track = buildTrack(item);
            if (track != null) {
                tracks.add(track);
            }
        }

        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        String title = getText(body, "title");
        String artworkUrl = getText(body, "artwork_url");
        String author = getText(body.get("user"), "username");

        return new ExtendedAudioPlaylist(
            title != null ? title : "SoundCloud Playlist",
            tracks,
            ExtendedAudioPlaylist.Type.PLAYLIST,
            url,
            artworkUrl,
            author,
            tracks.size()
        );
    }

    private AudioTrack buildTrack(JsonBrowser item) {
        if (item == null || item.isNull()) {
            return null;
        }

        String title = getText(item, "title");
        String identifier = getText(item, "id");
        long duration = item.get("duration").asLong(0L);

        if (title == null || identifier == null || duration <= 0L) {
            return null;
        }

        String author = getText(item.get("user"), "username");
        String uri = getText(item, "permalink_url");
        String artworkUrl = getText(item, "artwork_url");
        String artistUrl = getText(item.get("user"), "permalink_url");
        String artistArtworkUrl = getText(item.get("user"), "avatar_url");
        String isrc = getText(item.get("publisher_metadata"), "isrc");

        AudioTrackInfo info = new AudioTrackInfo(
            title,
            author != null && !author.isBlank() ? author : "Unknown",
            duration,
            identifier,
            false,
            uri != null ? uri : (WEBSITE_URL + "/" + identifier),
            artworkUrl,
            isrc
        );

        return new SoundCloudAudioTrack(info, null, null, artistUrl, artistArtworkUrl, null, false, this);
    }

    private void ensureClientId() throws IOException {
        try (HttpInterface httpInterface = this.httpInterfaceManager.getInterface()) {
            ensureClientId(httpInterface);
        }
    }

    private synchronized void ensureClientId(HttpInterface httpInterface) throws IOException {
        if (this.clientId != null && !this.clientId.isBlank()) {
            return;
        }

        String mainPage = fetchText(httpInterface, WEBSITE_URL);
        if (mainPage == null || mainPage.isBlank()) {
            throw new IOException("SoundCloud main page could not be loaded");
        }

        String found = extractClientId(mainPage);
        if (found == null) {
            List<String> assets = new ArrayList<>();
            Matcher matcher = ASSET_PATTERN.matcher(mainPage);
            while (matcher.find()) {
                assets.add(matcher.group());
            }

            for (String assetUrl : assets) {
                String assetBody = fetchText(httpInterface, assetUrl);
                if (assetBody == null || assetBody.isBlank()) {
                    continue;
                }
                found = extractClientId(assetBody);
                if (found != null) {
                    break;
                }
            }
        }

        if (found == null) {
            throw new IOException("SoundCloud client_id not found");
        }

        this.clientId = found;
        log.info("Loaded SoundCloud client_id: {}", this.clientId);
    }

    private String extractClientId(String body) {
        Matcher matcher = CLIENT_ID_PATTERN.matcher(body);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private JsonBrowser getJson(String url) throws IOException {
        try (HttpInterface httpInterface = this.httpInterfaceManager.getInterface()) {
            return getJson(httpInterface, url);
        }
    }

    private JsonBrowser getJson(HttpInterface httpInterface, String url) throws IOException {
        return PulseLinkTools.fetchResponseAsJson(httpInterface, createRequest(url));
    }

    private String fetchText(HttpInterface httpInterface, String url) throws IOException {
        try (CloseableHttpResponse response = httpInterface.execute(createRequest(url))) {
            if (response.getEntity() == null) {
                return null;
            }
            return IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
        }
    }

    private String resolveStreamUrl(HttpInterface httpInterface, String transcodingUrl) throws IOException {
        String requestUrl = transcodingUrl + (transcodingUrl.contains("?") ? "&" : "?") + "client_id=" + encode(this.clientId);
        try (CloseableHttpResponse response = httpInterface.execute(createRequest(requestUrl))) {
            int status = response.getStatusLine().getStatusCode();
            String location = response.getFirstHeader("Location") != null ? response.getFirstHeader("Location").getValue() : null;
            if (location != null && !location.isBlank()) {
                return location;
            }

            if (response.getEntity() != null) {
                String body = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
                if (body != null && !body.isBlank()) {
                    try {
                        JsonBrowser json = JsonBrowser.parse(body);
                        String url = getText(json, "url");
                        if (url != null) {
                            return url;
                        }
                    } catch (Exception ignored) {
                        // Not JSON; fall through.
                    }
                }
            }

            if (status >= 200 && status < 300) {
                return requestUrl;
            }
        }
        return null;
    }

    private JsonBrowser selectTranscoding(List<JsonBrowser> transcodings) {
        JsonBrowser progressiveMp3 = findTranscoding(transcodings, "progressive", "mpeg", null);
        JsonBrowser progressiveAac = findTranscoding(transcodings, "progressive", "aac", null);
        JsonBrowser hlsAacHigh = findTranscoding(transcodings, "hls", "aac", t -> {
            String quality = getText(t, "quality");
            String preset = getText(t, "preset");
            String url = getText(t, "url");
            return (quality != null && quality.equalsIgnoreCase("hq")) ||
                (preset != null && preset.contains("160")) ||
                (url != null && url.contains("160"));
        });
        JsonBrowser hlsAacStandard = findTranscoding(transcodings, "hls", "aac", null);
        JsonBrowser anyProgressive = findTranscoding(transcodings, "progressive", null, null);
        JsonBrowser anyHls = findTranscoding(transcodings, "hls", null, null);

        if (progressiveMp3 != null) return progressiveMp3;
        if (progressiveAac != null) return progressiveAac;
        if (hlsAacHigh != null) return hlsAacHigh;
        if (hlsAacStandard != null) return hlsAacStandard;
        if (anyProgressive != null) return anyProgressive;
        if (anyHls != null) return anyHls;

        return transcodings.isEmpty() ? null : transcodings.get(0);
    }

    private JsonBrowser findTranscoding(List<JsonBrowser> transcodings, String protocol, String mimeIncludes, java.util.function.Predicate<JsonBrowser> extraFilter) {
        for (JsonBrowser t : transcodings) {
            if (t == null || t.isNull()) continue;
            String p = getText(t.get("format"), "protocol");
            if (protocol != null && (p == null || !p.equalsIgnoreCase(protocol))) continue;
            String mime = getText(t.get("format"), "mime_type");
            if (mimeIncludes != null && (mime == null || !mime.toLowerCase(Locale.ROOT).contains(mimeIncludes))) continue;
            if (extraFilter != null && !extraFilter.test(t)) continue;
            return t;
        }
        return null;
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

    private String getText(JsonBrowser node, String field) {
        if (node == null || node.isNull()) return null;
        return getText(node.get(field));
    }

    private String getText(JsonBrowser node) {
        if (node == null || node.isNull()) return null;
        String text = node.text();
        return text != null && !text.isBlank() ? text : null;
    }

    private String buildParams(Map<String, String> params) {
        return params.entrySet().stream()
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .reduce((a, b) -> a + "&" + b)
            .orElse("");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
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

    @Override
    public void shutdown() {
        try {
            this.httpInterfaceManager.close();
        } catch (IOException e) {
            log.error("Failed to close SoundCloud HTTP interface manager", e);
        }
    }

    enum Protocol {
        HLS,
        PROGRESSIVE
    }

    static final class StreamInfo {
        private final String streamUrl;
        private final Protocol protocol;
        private final String mimeType;

        StreamInfo(String streamUrl, Protocol protocol, String mimeType) {
            this.streamUrl = streamUrl;
            this.protocol = protocol;
            this.mimeType = mimeType;
        }

        String streamUrl() {
            return this.streamUrl;
        }

        Protocol protocol() {
            return this.protocol;
        }

        String mimeType() {
            return this.mimeType;
        }
    }

    public static class SoundCloudConfig {
        private static final int DEFAULT_SEARCH_LIMIT = 10;
        private static final int DEFAULT_PLAYLIST_LIMIT = 100;

        private String clientId;
        private int searchLimit = DEFAULT_SEARCH_LIMIT;
        private int playlistLoadLimit = DEFAULT_PLAYLIST_LIMIT;

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public int getSearchLimit() {
            return searchLimit;
        }

        public void setSearchLimit(int searchLimit) {
            if (searchLimit < 1) {
                throw new IllegalArgumentException("searchLimit must be greater than 0");
            }
            this.searchLimit = searchLimit;
        }

        public int getPlaylistLoadLimit() {
            return playlistLoadLimit;
        }

        public void setPlaylistLoadLimit(int playlistLoadLimit) {
            if (playlistLoadLimit < 1) {
                throw new IllegalArgumentException("playlistLoadLimit must be greater than 0");
            }
            this.playlistLoadLimit = playlistLoadLimit;
        }
    }
}
