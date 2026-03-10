package com.github.itzrandom23.pulselink.protocol

import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val spotify: SpotifyConfig? = null,
    val appleMusic: AppleMusicConfig? = null,
    val deezer: DeezerConfig? = null,
    val yandexMusic: YandexMusicConfig? = null,
    val vkMusic: VkMusicConfig? = null,
    val qobuz: QobuzConfig? = null,
    val ytdlp: YtdlpConfig? = null,
)

@Serializable
data class SpotifyConfig(
    val countryCode: String? = "US",
    val playlistLoadLimit: Int? = 6,
    val albumLoadLimit: Int? = 6,
    val resolveArtistsInSearch: Boolean? = true,
    val localFiles: Boolean? = false,
)

@Serializable
data class AppleMusicConfig(
    val mediaAPIToken: String? = null,
)

@Serializable
data class DeezerConfig(
    val arl: String? = null,
    val formats: List<DeezerTrackFormat>? = null,
)

@Suppress("unused")
@Serializable
enum class DeezerTrackFormat {
    FLAC,
    MP3_320,
    MP3_256,
    MP3_128,
    MP3_64,
    AAC_64
}

@Serializable
data class YandexMusicConfig(
    val accessToken: String? = null,
)

@Serializable
data class VkMusicConfig(
    val userToken: String? = null,
)

@Serializable
data class QobuzConfig(
    val userOauthToken: String? = null,
    val appId: String? = null,
    val appSecret: String? = null,
)

@Serializable
data class YtdlpConfig(
    val path: String? = null,
    val searchLimit: Int? = null,
    val customLoadArgs: List<String>? = null,
    val customPlaybackArgs: List<String>? = null,
)
