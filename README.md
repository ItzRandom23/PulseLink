# PulseLink

PulseLink is a Lavalink/Lavaplayer plugin that resolves metadata from multiple music services and mirrors playback through your configured providers. Some sources also support direct playback.

Amazon Music, Qobuz, and YouTube integrations (including yt-dlp) have been removed. YouTube support was removed as a precaution because of legal concerns around Discord music bots that support YouTube.

## Quick Start

1. Add PulseLink to your Lavalink `application.yml` with JitPack:

```yaml
plugins:
  - dependency: "com.github.ItzRandom23:PulseLink:v1.8.2"
    repository: "https://jitpack.io"
    snapshot: false
```

2. Use [`application.example.yml`](https://github.com/ItzRandom23/PulseLink/blob/main/application.example.yml) as the full configuration reference.
3. Start Lavalink.

## Configuration

See [`application.example.yml`](https://github.com/ItzRandom23/PulseLink/blob/main/application.example.yml) for the current full example configuration.

### Lyrics

Enable LRCLIB lyrics with:

```yaml
plugins:
  pulselink:
    lyrics-sources:
      lrcLib: true
```

LRCLIB searches by the loaded track's title and artist and returns plain lyrics, synchronized lyrics, or both when available.

The Lavalink lyrics endpoint is provided by the [LavaLyrics plugin](https://github.com/topi314/LavaLyrics); install it alongside PulseLink when you need `GET /v4/sessions/{sessionId}/players/{guildId}/track/lyrics`.

## Providers and Mirroring

PulseLink mirrors playback for services that do not stream directly.

When a track is requested, PulseLink builds a provider query using the track ISRC if available, then falls back to a title/artist query. Configure the fallback order with `plugins.pulselink.providers`. There is no implicit YouTube fallback; YouTube, yt-dlp, Amazon Music, and Qobuz provider entries are ignored.

Playback modes:
- Mirror: metadata only, audio comes from your configured providers.
- Direct: PulseLink streams audio directly.

| Source | Access | Notes |
|--------|--------|-------|
| Spotify | Mirror | Default resolver URLs are provided for search and resolution. `anonymousTokenUrl` is used for Spotify mix fallback. |
| Apple Music | Mirror | `mediaAPIToken` is optional. |
| Tidal | Mirror / Direct | Uses the built-in default token; direct streaming also needs hifi-api. |
| Deezer | Direct | Requires `arl` and `masterDecryptionKey`. |
| Yandex Music | Direct | Requires `accessToken`. |
| VK Music | Direct | Requires `userToken`. |
| JioSaavn | Direct | Uses built-in decryption defaults; `decryption` can be overridden in config. |
| Audiomack | Direct | Uses built-in web credentials; `consumerKey` and `consumerSecret` can be overridden. |
| Gaana | Direct | No credentials required. |
| SoundCloud | Direct / Mirror fallback | No credentials required. Supports tracks, sets/playlists, user pages, `m.soundcloud.com`, `on.soundcloud.com`, and `snd.sc` links. Full SoundCloud results can be selected as mirrors; preview-only results proceed to the next provider. |
| Pandora | Mirror | Uses the built-in remote token provider; `remoteTokenUrl`, `csrfToken`, or `authToken` can override it. |
| FloweryTTS | Direct | No credentials required. |

Credentials and external requirements:
- No credentials required: Spotify, Pandora, Gaana, SoundCloud, FloweryTTS
- Spotify track-mix recommendations first request one direct recommendation from the configured Spotify resolver. Album and artist mixes, and track mixes with no direct result, use Spotify's inspired-by mix API, which uses the configured anonymous token endpoint. Default endpoint URLs are included; override them in `plugins.pulselink.spotify.apiUrl` and `plugins.pulselink.spotify.anonymousTokenUrl` if you host your own services.
- Optional overrides: Apple Music `mediaAPIToken`, Tidal `token` or `appId`/`appSecret`, Audiomack `consumerKey` / `consumerSecret`, JioSaavn `decryption`, Pandora `remoteTokenUrl` / `csrfToken` / `authToken`
- Required credentials: Deezer `arl` and `masterDecryptionKey`, Yandex Music `accessToken`, VK Music `userToken`

Supported search prefixes:
- Spotify: `spsearch:query`
- Spotify mix recommendations: `sprec:mix:track:id` requests one direct next-track recommendation before fallback; `sprec:mix:album:id` and `sprec:mix:artist:id` use Spotify's inspired-by mix flow.
- Apple Music: `amsearch:query`
- Deezer: `dzsearch:query`
- Yandex Music: `ymsearch:query`
- VK Music: `vksearch:query`
- Tidal: `tdsearch:query`
- JioSaavn: `jssearch:query`
- Audiomack: `admsearch:query`
- Gaana: `gnsearch:query`
- SoundCloud: `scsearch:query`
- Pandora: `pdsearch:query`

SoundCloud fallback notes:
- If SoundCloud only exposes a 30-second preview, PulseLink resolves a mirror during load and uses the mirror track duration for the queued SoundCloud track.
- `scsearch:` may be included in mirror providers: playable full SoundCloud search results are used, while preview-only results are skipped without recursive SoundCloud searching.

SoundCloud client-ID discovery first tries the homepage, then the search page. If discovery fails, PulseLink waits 30 seconds before trying again so concurrent searches do not repeatedly request a blocked website.

If your server receives `403 Forbidden` from SoundCloud's website, you can skip discovery with an existing valid SoundCloud **web** client ID:

```yaml
plugins:
  pulselink:
    soundcloud:
      clientId: "${SOUNDCLOUD_CLIENT_ID:}"
```

Use the `client_id` from a working SoundCloud web API request, not an OAuth access token or client secret. A blank value keeps automatic discovery enabled. If a configured ID is rejected, PulseLink reports it without scraping the blocked website or retrying the same ID. Update the value and restart Lavalink.

If SoundCloud also blocks API requests from your server, a client ID alone cannot restore access. You can configure `plugins.pulselink.soundcloud.proxy` with `url` and optional `username` / `password`, using the same proxy configuration as VK Music and Yandex Music. The proxy applies to Java HTTP requests, including discovery, API requests and progressive downloads; FFmpeg's HLS downloads still use its own network configuration. Keep `plugins.pulselink.sources.soundcloud: true` and `scsearch:%QUERY%` in your provider list to use SoundCloud; Lavalink's built-in SoundCloud flag does not control PulseLink's source.

## Troubleshooting

Common checks:
- If mirrored tracks do not resolve, verify your `providers` order and keep a `%QUERY%` fallback after `%ISRC%`.
- The runtime PATCH endpoint only updates mutable fields present in the payload; null fields are ignored.
- Local Gradle builds and JitPack builds require Java 17.

## Contributing

- Prefer small, focused changes.
- Add deterministic unit tests for resolver logic, registration behavior, and config/status safety when you touch those areas.
- Update this README when config, release expectations, or operational behavior changes.

## Security

Please report security issues privately through GitHub Security Advisories for this repository rather than opening a public issue.

## Credits

PulseLink is based on LavaSrc by topi314 and community contributors.  
Additional source adapters and cleanup by ItzRandom23.
