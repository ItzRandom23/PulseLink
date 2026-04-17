# PulseLink

PulseLink is a Lavalink/Lavaplayer plugin that resolves metadata from multiple music services and mirrors playback through your own providers (for example YouTube search). Some sources also support direct playback.

## Quick Start

1. Add PulseLink to your Lavalink `application.yml` with JitPack:

```yaml
plugins:
  - dependency: "com.github.ItzRandom23:PulseLink:v1.5.8"
    repository: "https://jitpack.io"
    snapshot: false
```

2. Use [`application.example.yml`](https://github.com/ItzRandom23/PulseLink/blob/main/application.example.yml) as the full configuration reference.
3. Start Lavalink.

## Configuration

See [`application.example.yml`](https://github.com/ItzRandom23/PulseLink/blob/main/application.example.yml) for the current full example configuration.

## Providers and Mirroring

PulseLink mirrors playback for services that do not stream directly.

When a track is requested, PulseLink builds a provider query using the track ISRC if available, then falls back to a title/artist query. Configure the fallback order with `plugins.pulselink.providers`.

Playback modes:
- Mirror: metadata only, audio comes from your configured providers.
- Direct: PulseLink streams audio directly.

| Source | Access | Notes |
|--------|--------|-------|
| Spotify | Mirror | `apiUrl` and `anonymousTokenUrl` are optional. |
| Amazon Music | Mirror | No credentials required. |
| Apple Music | Mirror | `mediaAPIToken` is optional. |
| Tidal | Mirror / Direct | Uses the built-in default token; direct streaming also needs hifi-api. |
| Qobuz | Mirror | No manual credentials required; optional app/user credentials can be supplied. |
| Deezer | Direct | Requires `arl` and `masterDecryptionKey`. |
| Yandex Music | Direct | Requires `accessToken`. |
| VK Music | Direct | Requires `userToken`. |
| JioSaavn | Direct | Uses built-in decryption defaults; `decryption` can be overridden in config. |
| Audiomack | Direct | No credentials required. |
| Gaana | Direct | No credentials required. |
| Shazam | Mirror | No credentials required. |
| Pandora | Mirror | Uses the built-in remote token provider; `remoteTokenUrl`, `csrfToken`, or `authToken` can override it. |
| yt-dlp | Direct | Requires `yt-dlp` installed. |
| FloweryTTS | Direct | No credentials required. |
| YouTube | Search / Lyrics | Requires the new YouTube source plugin. |

Credentials and external requirements:
- No credentials required: Spotify, Amazon Music, Qobuz, Shazam, Pandora, Audiomack, Gaana, FloweryTTS
- Spotify mix recommendations use an anonymous token endpoint. By default PulseLink expects `http://us2.leonodes.xyz:15540/api/token`, and you can override it with `plugins.pulselink.spotify.anonymousTokenUrl`.
- Optional overrides: Apple Music `mediaAPIToken`, Tidal `token`, Qobuz `userOauthToken` or `appId`/`appSecret`, JioSaavn `decryption`, Pandora `remoteTokenUrl` / `csrfToken` / `authToken`
- Required credentials: Deezer `arl` and `masterDecryptionKey`, Yandex Music `accessToken`, VK Music `userToken`
- Other required setup: `yt-dlp` installed for yt-dlp, and the new YouTube source plugin for YouTube search / lyrics

Supported search prefixes:
- Spotify: `spsearch:query`
- Spotify mix recommendations: `sprec:mix:track:id`, `sprec:mix:album:id`, `sprec:mix:artist:id`
- Amazon Music: `amzsearch:query`
- Apple Music: `amsearch:query`
- Deezer: `dzsearch:query`
- Yandex Music: `ymsearch:query`
- VK Music: `vksearch:query`
- Tidal: `tdsearch:query`
- Qobuz: `qbsearch:query`
- JioSaavn: `jssearch:query`
- Audiomack: `admsearch:query`
- Gaana: `gnsearch:query`
- Shazam: `szsearch:query`
- Pandora: `pdsearch:query`
- yt-dlp: `ytsearch:query`
- YouTube Music autocomplete/search: `ytmsearch:query`

## Troubleshooting

Common checks:
- If mirrored tracks do not resolve, verify your `providers` order and keep a `%QUERY%` fallback after `%ISRC%`.
- If YouTube search or lyrics is enabled, the new YouTube source plugin must also be available.
- The runtime PATCH endpoint only updates mutable fields present in the payload; null fields are ignored.
- Local Gradle builds require Java 17.

## Contributing

- Prefer small, focused changes.
- Add deterministic unit tests for resolver logic, registration behavior, and config/status safety when you touch those areas.
- Update this README when config, release expectations, or operational behavior changes.

## Security

Please report security issues privately through GitHub Security Advisories for this repository rather than opening a public issue.

## Credits

PulseLink is based on LavaSrc by topi314 and community contributors.  
Additional source adapters and cleanup by ItzRandom23.
