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
  - dependency: "com.github.itzrandom23.pulselink:pulselink-plugin:v1.4.0"
    repository: "https://jitpack.io"
    snapshot: false
```

Replace `v1.4.0` with the Git tag you published.

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
      countryCode: "US"
      playlistLoadLimit: 6
      albumLoadLimit: 6
      resolveArtistsInSearch: true
      localFiles: false
    amazonmusic:
      # Optional. 0-10
      searchLimit: 10
    audiomack:
      searchLimit: 10
      artistTrackLimit: 25
    gaana:
      # Optional. Defaults to https://gaanapi-wine.vercel.app/
      apiUrl: "https://gaanapi-wine.vercel.app/"
```

## Releases

PulseLink ships jars through GitHub Releases and the GitHub Actions build artifacts.
The GitHub Packages page is intended for the container image only, not Maven jars.

## Providers and Mirroring

PulseLink mirrors playback for services that do not stream directly (Spotify, Amazon Music, Apple Music, Tidal, Qobuz, Shazam).  
When a track is requested, PulseLink builds a provider query using the track ISRC if available, or a title/artist search fallback.

Use the `plugins.pulselink.providers` list to decide where mirrored playback should be sourced from.

## Sources

Playback modes:
- Mirror: metadata only, audio comes from your providers.
- Direct: PulseLink streams audio directly.

| Source     | Playback | Notes |
|------------|----------|-------|
| Spotify    | Mirror   | No credentials required |
| Amazon Music | Mirror | No credentials required |
| Apple Music| Mirror   | Requires Apple Music API token or MusicKit key |
| Tidal      | Mirror   | Uses provider mirroring |
| Qobuz      | Mirror   | Uses provider mirroring |
| Deezer     | Direct   | Requires Deezer ARL and master key |
| Yandex     | Direct   | Requires access token |
| VK Music   | Direct   | Requires user token |
| JioSaavn   | Direct   | Requires decryption key |
| Audiomack  | Direct   | Some regions return no stream URL |
| Gaana      | Direct   | Uses configurable Gaana API (default included) |
| Shazam     | Mirror   | No credentials required |
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
- yt-dlp: `ytsearch:query`

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

## Region Notes

- Audiomack may return a null stream URL in restricted regions.
- Gaana uses direct HLS playback through the configured Gaana API endpoint.
- Shazam resolves metadata directly and mirrors playback through `providers`.
- Yandex and VK are region locked in some locations (use a proxy if needed).

## Credits

PulseLink is based on LavaSrc by topi314 and community contributors.  
Additional source adapters and cleanup by ItzRandom23.
