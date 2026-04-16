# PulseLink

PulseLink is a Lavalink/Lavaplayer plugin that resolves metadata from multiple music services and mirrors playback through your own providers (for example YouTube search). Some sources also support direct playback.

## Quick Start

1. Build the plugin with `./gradlew :plugin:jar`.
2. Copy the jar from `plugin/build/libs/` into your Lavalink `plugins` folder.
3. Add the config shown below to your `application.yml`.
4. Start Lavalink.

## Documentation

- Local Docker example: `docker/docker-compose.example.yml`
- Local application example: `docker/application.local.example.yml`
- Full sample config: `application.example.yml`

## JitPack Install

If you want Lavalink to pull the plugin automatically via JitPack, add this to `application.yml`:

```yaml
plugins:
  - dependency: "com.github.ItzRandom23:PulseLink:v1.5.5"
    repository: "https://jitpack.io"
    snapshot: false
```

## Configuration

Minimal example for Lavalink:

```yaml
plugins:
  pulselink:
    providers:
      - "ytsearch:\"%ISRC%\""
      - "ytsearch:%QUERY%"
    sources:
      spotify: true
      amazonmusic: false
      applemusic: false
      deezer: false
      yandexmusic: false
      vkmusic: false
      tidal: false
      qobuz: false
      ytdlp: false
      jiosaavn: false
      audiomack: false
      gaana: false
      shazam: false
      flowerytts: false
      youtube: false
    lyrics-sources:
      deezer: false
      youtube: false
      yandexmusic: false
      vkmusic: false
      lrcLib: false
    spotify:
      # Optional. Defaults to http://us2.leonodes.xyz:15561
      apiUrl: "http://us2.leonodes.xyz:15561"
      countryCode: "US"
      playlistLoadLimit: 6
      albumLoadLimit: 6
      resolveArtistsInSearch: true
      localFiles: false
    amazonmusic:
      # Optional. 0-10
      searchLimit: 10
    applemusic:
      countryCode: "US"
      # Optional. Apple Music playback can work without this token.
      mediaAPIToken: "your apple music api token"
    tidal:
      countryCode: "US"
      searchLimit: 6
      token: "i4ZDjcyhed7Mu47q"
      # Optional: direct streaming via hifi-api (third-party)
      hifiApis:
        - "https://hifi-two.spotisaver.net"
      # Optional: order of qualities to try for direct streaming
      hifiQualities: [ "HI_RES_LOSSLESS", "LOSSLESS", "HIGH", "LOW" ]
    jiosaavn:
      decryption:
        algorithm: "DES"
        secretKey: "38346591"
        transformation: "DES/ECB/PKCS5Padding"
    ytdlp:
      path: "yt-dlp"
      searchLimit: 10
    audiomack:
      searchLimit: 10
      artistTrackLimit: 25
    gaana:
      # Optional. Defaults to https://gaanapi-wine.vercel.app/
      apiUrl: "https://gaanapi-wine.vercel.app/"
    pandora:
      # Optional. If omitted, PulseLink will try guest bootstrap automatically.
      csrfToken: ""
      # Optional pre-fetched auth token.
      authToken: ""
      # Optional token provider returning { success, authToken, csrfToken }.
      remoteTokenUrl: "https://get.1lucas1apk.fun/pandora/gettoken"
```

## Runtime Status

PulseLink exposes a read-only status endpoint at `GET /v4/pulselink/status`.

The response includes:

- enabled sources
- enabled lyrics sources
- provider templates
- readiness issues such as missing credentials or missing YouTube plugin support

Secrets are not included in the response.

## Providers and Mirroring

PulseLink mirrors playback for services that do not stream directly.

When a track is requested, PulseLink builds a provider query using the track ISRC if available, then falls back to a title/artist query. Configure the fallback order with `plugins.pulselink.providers`.

Playback modes:
- Mirror: metadata only, audio comes from your configured providers.
- Direct: PulseLink streams audio directly.

| Source | Access | Notes |
|--------|--------|-------|
| Spotify | Mirror | `apiUrl` is optional. |
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
- Optional overrides: Apple Music `mediaAPIToken`, Tidal `token`, Qobuz `userOauthToken` or `appId`/`appSecret`, JioSaavn `decryption`, Pandora `remoteTokenUrl` / `csrfToken` / `authToken`
- Required credentials: Deezer `arl` and `masterDecryptionKey`, Yandex Music `accessToken`, VK Music `userToken`
- Other required setup: `yt-dlp` installed for yt-dlp, and the new YouTube source plugin for YouTube search / lyrics

Supported search prefixes:
- Spotify: `spsearch:query`
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

## Releases

PulseLink ships jars through GitHub Releases and GitHub Actions build artifacts. The GitHub Packages page is intended for the container image only, not Maven jars.

Versioning is currently manual in `build.gradle`.

Artifacts:
- Plugin jar: `plugin/build/libs/pulselink-plugin-<version>.jar`
- Main jar: `main/build/libs/pulselink-<version>.jar`
- Container image: `ghcr.io/itzrandom23/pulselink:<tag>`

## Local Docker

Example local deployment files are included:
- `docker/docker-compose.example.yml`
- `docker/application.local.example.yml`

They provide a minimal Lavalink + PulseLink setup using the published container image.

## Troubleshooting

Common checks:
- If mirrored tracks do not resolve, verify your `providers` order and keep a `%QUERY%` fallback after `%ISRC%`.
- If YouTube search or lyrics is enabled, the new YouTube source plugin must also be available.
- If a source appears enabled but unusable, check `GET /v4/pulselink/status` for readiness issues.
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
