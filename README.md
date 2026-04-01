# PulseLink

PulseLink is a Lavalink/Lavaplayer plugin that resolves metadata from multiple music services and mirrors playback through your own providers (for example YouTube search). Some sources also support direct playback.

## Quick Start

1. Build the plugin:
```
./gradlew :plugin:jar
```
2. Copy the jar from `plugin/build/libs/` into your Lavalink `plugins` folder.
3. Add the config shown below to your `application.yml`.
4. Start Lavalink.

## JitPack Install

If you want Lavalink to pull the plugin automatically via JitPack, add this to `application.yml`:

```yaml
plugins:
  - dependency: "com.github.ItzRandom23:PulseLink:v1.5.4"
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
      mediaAPIToken: "your apple music api token"
    tidal:
      countryCode: "US"
      searchLimit: 6
      token: "your tidal token"
      # Optional: direct streaming via hifi-api (third-party)
      hifiApis:
        - "https://hifi-two.spotisaver.net"
      # Optional: order of qualities to try for direct streaming
      hifiQualities: [ "HI_RES_LOSSLESS", "LOSSLESS", "HIGH", "LOW" ]
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

## Releases

PulseLink ships jars through GitHub Releases and the GitHub Actions build artifacts.
The GitHub Packages page is intended for the container image only, not Maven jars.

## Providers and Mirroring

PulseLink mirrors playback for services that do not stream directly (Spotify, Amazon Music, Apple Music, Tidal, Qobuz, Shazam, Pandora).  
When a track is requested, PulseLink builds a provider query using the track ISRC if available, or a title/artist search fallback.

Use the `plugins.pulselink.providers` list to decide where mirrored playback should be sourced from.

The most complete config reference lives in [application.example.yml](https://github.com/ItzRandom23/PulseLink/blob/main/application.example.yml).

## Sources

Playback modes:
- Mirror: metadata only, audio comes from your providers.
- Direct: PulseLink streams audio directly.

| Source     | Playback | Notes |
|------------|----------|-------|
| Spotify    | Mirror   | No credentials required |
| Amazon Music | Mirror | No credentials required |
| Apple Music| Mirror   | Requires an Apple Music `mediaAPIToken` |
| Tidal      | Mirror / Direct | Direct requires hifi-api; otherwise mirrors |
| Qobuz      | Mirror   | Uses provider mirroring |
| Deezer     | Direct   | Requires Deezer ARL and master key |
| Yandex     | Direct   | Requires access token |
| VK Music   | Direct   | Requires user token |
| JioSaavn   | Direct   | Requires decryption key |
| Audiomack  | Direct   | Some regions return no stream URL |
| Gaana      | Direct   | Uses configurable Gaana API (default included) |
| Shazam     | Mirror   | No credentials required |
| Pandora    | Mirror   | Guest bootstrap works automatically; remote token URL is optional |
| yt-dlp     | Direct   | Requires `yt-dlp` installed |
| FloweryTTS | Direct   | Optional TTS service |
| YouTube    | Search   | Requires LavaSearch plugin |

## Supported Queries

Search prefixes:
- Spotify: `spsearch:query`
- Amazon Music: `amzsearch:query`
- Apple Music: `amsearch:query`
- Deezer: `dzsearch:query`
- Yandex: `ymsearch:query`
- VK: `vksearch:query`
- Tidal: `tdsearch:query`
- Qobuz: `qbsearch:query`
- JioSaavn: `jssearch:query`
- Audiomack: `admsearch:query`
- Gaana: `gnsearch:query`
- Shazam: `szsearch:query`
- Pandora: `pdsearch:query`
- yt-dlp: `ytsearch:query`
- YouTube Music autocomplete/search: `ytmsearch:query`

Common URLs:
- Spotify: `https://open.spotify.com/track/...`
- Amazon Music: `https://music.amazon.com/tracks/...`
- Apple Music: `https://music.apple.com/...`
- Deezer: `https://www.deezer.com/track/...`
- Yandex: `https://music.yandex.ru/track/...`
- VK: `https://vk.com/audio...`
- Tidal: `https://tidal.com/browse/track/...`
- Qobuz: `https://open.qobuz.com/track/...`
- JioSaavn: `https://www.jiosaavn.com/song/...`
- Audiomack: `https://audiomack.com/artist/song/...`
- Gaana: `https://gaana.com/song/...`
- Shazam: `https://www.shazam.com/song/...`
- Pandora: `https://www.pandora.com/playlist/...`
- Pandora: `https://www.pandora.com/station/...`
- Pandora: `https://www.pandora.com/podcast/...`
- Pandora: `https://www.pandora.com/artist/...`

## Region Notes

- Audiomack may return a null stream URL in restricted regions.
- Gaana uses direct HLS playback through the configured Gaana API endpoint.
- Pandora may need a remote token provider if guest bootstrap is blocked in your region or environment.
- Shazam resolves metadata directly and mirrors playback through `providers`.
- Yandex and VK are region locked in some locations (use a proxy if needed).
- Tidal direct streaming relies on a third-party hifi-api and currently supports FLAC/MP3 manifests (MP4/AAC manifests will fall back to mirroring).
- `https://hifi-two.spotisaver.net` is one working `hifiApi` option, based on `https://github.com/uimaxbai/hifi-api`.
- `https://get.1lucas1apk.fun/pandora/gettoken` is one working Pandora `remoteTokenUrl` option for guest token bootstrap.

## Credits

PulseLink is based on LavaSrc by topi314 and community contributors.  
Additional source adapters and cleanup by ItzRandom23.
