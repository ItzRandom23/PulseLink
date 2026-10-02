# PulseLink v1.7.2

Fixes SoundCloud authentication failures and slow playlist loading that could cause client timeouts and broken-pipe responses.

- Validate discovered SoundCloud client IDs before caching them.
- Refresh a rejected client ID and retry once on SoundCloud API 401/403 responses. Rate limits and other errors do not trigger authentication retries.
- Load playlist metadata without probing every track's stream or mirror providers.
- Fetch metadata for sparse playlist entries in batches of 50, preserving playlist order and omitting unavailable entries.
- Correct HLS parsing when the supplied URL already points to a media playlist.

## Installation

Update the Lavalink plugin dependency:

```yaml
lavalink:
  plugins:
    - dependency: "com.github.ItzRandom23:PulseLink:v1.7.2"
      repository: "https://jitpack.io"
      snapshot: false
```

Alternatively, download `PulseLink-v1.7.2.jar` from this release and install it in Lavalink's `plugins` folder. Remove the previous PulseLink JAR so only one version is loaded, then restart Lavalink. Existing source configuration remains compatible.

## Validation

SoundCloud regression tests cover authentication refresh, bounded retries, rate limits, client-ID validation and caching, sparse playlist hydration, metadata-only loading, and HLS playlist parsing. An optional live test checks the reported 72-track SoundCloud playlist against a 15-second client timeout.
