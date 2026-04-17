package com.github.itzrandom23.pulselink.plugin;

import com.github.topi314.lavalyrics.LyricsManager;
import com.github.topi314.lavalyrics.api.LyricsManagerConfiguration;
import com.github.topi314.lavasearch.SearchManager;
import com.github.topi314.lavasearch.api.SearchManagerConfiguration;
import com.github.itzrandom23.pulselink.applemusic.AppleMusicSourceManager;
import com.github.itzrandom23.pulselink.amazonmusic.AmazonMusicSourceManager;
import com.github.itzrandom23.pulselink.audiomack.AudiomackAudioSourceManager;
import com.github.itzrandom23.pulselink.deezer.DeezerAudioSourceManager;
import com.github.itzrandom23.pulselink.deezer.DeezerAudioTrack;
import com.github.itzrandom23.pulselink.flowerytts.FloweryTTSSourceManager;
import com.github.itzrandom23.pulselink.gaana.GaanaAudioSourceManager;
import com.github.itzrandom23.pulselink.jiosaavn.JioSaavnAudioSourceManager;
import com.github.itzrandom23.pulselink.lrclib.LrcLibLyricsManager;
import com.github.itzrandom23.pulselink.mirror.DefaultMirroringAudioTrackResolver;
import com.github.itzrandom23.pulselink.pandora.PandoraSourceManager;
import com.github.itzrandom23.pulselink.plugin.config.*;
import com.github.itzrandom23.pulselink.plugin.service.ProxyConfigurationService;
import com.github.itzrandom23.pulselink.protocol.Config;
import com.github.itzrandom23.pulselink.qobuz.QobuzAudioSourceManager;
import com.github.itzrandom23.pulselink.shazam.ShazamAudioSourceManager;
import com.github.itzrandom23.pulselink.spotify.SpotifySourceManager;
import com.github.itzrandom23.pulselink.tidal.TidalSourceManager;
import com.github.itzrandom23.pulselink.vkmusic.VkMusicSourceManager;
import com.github.itzrandom23.pulselink.yandexmusic.YandexMusicSourceManager;
import com.github.itzrandom23.pulselink.youtube.YoutubeSearchManager;
import com.github.itzrandom23.pulselink.ytdlp.YtdlpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import dev.arbjerg.lavalink.api.AudioPlayerManagerConfiguration;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Service
@RestController
public class PulseLinkPlugin implements AudioPlayerManagerConfiguration, SearchManagerConfiguration, LyricsManagerConfiguration {

	private static final Logger log = LoggerFactory.getLogger(PulseLinkPlugin.class);

	private final SourcesConfig sourcesConfig;
	private final LyricsSourcesConfig lyricsSourcesConfig;
	private AudioPlayerManager manager;
	private SpotifySourceManager spotify;
	private AmazonMusicSourceManager amazonMusic;
	private AppleMusicSourceManager appleMusic;
	private DeezerAudioSourceManager deezer;
	private YandexMusicSourceManager yandexMusic;
	private FloweryTTSSourceManager flowerytts;
	private YoutubeSearchManager youtube;
	private VkMusicSourceManager vkMusic;
	private TidalSourceManager tidal;
	private JioSaavnAudioSourceManager jioSaavn;
	private QobuzAudioSourceManager qobuz;
	private YtdlpAudioSourceManager ytdlp;
	private AudiomackAudioSourceManager audiomack;
	private GaanaAudioSourceManager gaana;
	private ShazamAudioSourceManager shazam;
	private PandoraSourceManager pandora;
	private LrcLibLyricsManager lrcLib;

	public PulseLinkPlugin(
		PulseLinkConfig pluginConfig,
		SourcesConfig sourcesConfig,
		LyricsSourcesConfig lyricsSourcesConfig,
		SpotifyConfig spotifyConfig,
		AmazonMusicConfig amazonMusicConfig,
		AppleMusicConfig appleMusicConfig,
		DeezerConfig deezerConfig,
		YandexMusicConfig yandexMusicConfig,
		FloweryTTSConfig floweryTTSConfig,
		YouTubeConfig youTubeConfig,
		VkMusicConfig vkMusicConfig,
		TidalConfig tidalConfig,
		QobuzConfig qobuzConfig,
		YtdlpConfig ytdlpConfig,
		JioSaavnConfig jioSaavnConfig,
		AudiomackConfig audiomackConfig,
		GaanaConfig gaanaConfig,
		PandoraConfig pandoraConfig,
		ProxyConfigurationService proxyConfigurationService
	) {
		log.info("Loading PulseLink plugin...");
		this.sourcesConfig = sourcesConfig;
		this.lyricsSourcesConfig = lyricsSourcesConfig;

		if (sourcesConfig.isSpotify()) {
			this.spotify = new SpotifySourceManager(
				spotifyConfig.getCountryCode(),
				spotifyConfig.getApiUrl(),
				unused -> manager,
				new DefaultMirroringAudioTrackResolver(pluginConfig.getProviders())
			);
			this.spotify.setAnonymousTokenUrl(spotifyConfig.getAnonymousTokenUrl());

			if (spotifyConfig.getPlaylistLoadLimit() > 0) {
				this.spotify.setPlaylistPageLimit(spotifyConfig.getPlaylistLoadLimit());
			}
			if (spotifyConfig.getAlbumLoadLimit() > 0) {
				this.spotify.setAlbumPageLimit(spotifyConfig.getAlbumLoadLimit());
			}
			if (!spotifyConfig.isResolveArtistsInSearch()) {
				this.spotify.setResolveArtistsInSearch(spotifyConfig.isResolveArtistsInSearch());
			}
			if (spotifyConfig.isLocalFiles()) {
				this.spotify.setLocalFiles(spotifyConfig.isLocalFiles());
			}
		}

		if (sourcesConfig.isAmazonMusic()) {
			this.amazonMusic = new AmazonMusicSourceManager(
				pluginConfig.getProviders(),
				unused -> manager
			);
			int searchLimit = amazonMusicConfig.getSearchLimit();
			if (searchLimit < 0) {
				searchLimit = 0;
			} else if (searchLimit > 10) {
				searchLimit = 10;
			}
			this.amazonMusic.setSearchLimit(searchLimit);
		}

		if (sourcesConfig.isAppleMusic()) {
			this.appleMusic = new AppleMusicSourceManager(pluginConfig.getProviders(), appleMusicConfig.getMediaAPIToken(), appleMusicConfig.getCountryCode(), unused -> manager);
			if (appleMusicConfig.getPlaylistLoadLimit() > 0) {
				appleMusic.setPlaylistPageLimit(appleMusicConfig.getPlaylistLoadLimit());
			}
			if (appleMusicConfig.getAlbumLoadLimit() > 0) {
				appleMusic.setAlbumPageLimit(appleMusicConfig.getAlbumLoadLimit());
			}
		}
		if (sourcesConfig.isDeezer() || lyricsSourcesConfig.isDeezer()) {
			this.deezer = new DeezerAudioSourceManager(deezerConfig.getMasterDecryptionKey(), deezerConfig.getArl(), deezerConfig.getFormats());
		}

		if (sourcesConfig.isYandexMusic() || lyricsSourcesConfig.isYandexMusic()) {
			this.yandexMusic = new YandexMusicSourceManager(yandexMusicConfig.getAccessToken());

			proxyConfigurationService.configure(this.yandexMusic, yandexMusicConfig.getProxy());

			if (yandexMusicConfig.getPlaylistLoadLimit() > 0) {
				yandexMusic.setPlaylistLoadLimit(yandexMusicConfig.getPlaylistLoadLimit());
			}

			if (yandexMusicConfig.getAlbumLoadLimit() > 0) {
				yandexMusic.setAlbumLoadLimit(yandexMusicConfig.getAlbumLoadLimit());
			}

			if (yandexMusicConfig.getArtistLoadLimit() > 0) {
				yandexMusic.setArtistLoadLimit(yandexMusicConfig.getArtistLoadLimit());
			}

		}

		if (sourcesConfig.isFloweryTTS()) {
			this.flowerytts = new FloweryTTSSourceManager(floweryTTSConfig.getVoice());
			if (floweryTTSConfig.getTranslate()) {
				this.flowerytts.setTranslate(floweryTTSConfig.getTranslate());
			}
			if (floweryTTSConfig.getSilence() > 0) {
				this.flowerytts.setSilence(floweryTTSConfig.getSilence());
			}
			if (floweryTTSConfig.getSpeed() > 0) {
				this.flowerytts.setSpeed(floweryTTSConfig.getSpeed());
			}
			if (floweryTTSConfig.getAudioFormat() != null) {
				this.flowerytts.setAudioFormat(floweryTTSConfig.getAudioFormat());
			}
		}
		if (sourcesConfig.isYoutube() || lyricsSourcesConfig.isYoutube()) {
			if (hasNewYoutubeSource()) {
				log.info("Registering Youtube Source audio source manager...");
				this.youtube = new YoutubeSearchManager(() -> manager, youTubeConfig.getCountryCode(), youTubeConfig.getLanguage());
			} else {
				throw new IllegalStateException("Youtube LavaSearch requires the new Youtube Source plugin to be enabled.");
			}
		}
		if (sourcesConfig.isVkMusic() || lyricsSourcesConfig.isVkMusic()) {
			this.vkMusic = new VkMusicSourceManager(vkMusicConfig.getUserToken());
			proxyConfigurationService.configure(this.vkMusic, vkMusicConfig.getProxy());

			if (vkMusicConfig.getPlaylistLoadLimit() > 0) {
				vkMusic.setPlaylistLoadLimit(vkMusicConfig.getPlaylistLoadLimit());
			}
			if (vkMusicConfig.getArtistLoadLimit() > 0) {
				vkMusic.setArtistLoadLimit(vkMusicConfig.getArtistLoadLimit());
			}
			if (vkMusicConfig.getRecommendationLoadLimit() > 0) {
				vkMusic.setRecommendationsLoadLimit(vkMusicConfig.getRecommendationLoadLimit());
			}
		}
		if (sourcesConfig.isTidal()) {
			this.tidal = new TidalSourceManager(
				pluginConfig.getProviders(),
				tidalConfig.getCountryCode(),
				unused -> this.manager,
				tidalConfig.getToken(),
				tidalConfig.getHifiApis(),
				tidalConfig.getHifiQualities()
			);
			if (tidalConfig.getSearchLimit() > 0) {
				this.tidal.setSearchLimit(tidalConfig.getSearchLimit());
			}
		}
		if (sourcesConfig.isQobuz()) {
			this.qobuz = new QobuzAudioSourceManager(
				pluginConfig.getProviders(),
				unused -> this.manager,
				qobuzConfig.getUserOauthToken(),
				qobuzConfig.getAppId(),
				qobuzConfig.getAppSecret()
			);
		}
		if (sourcesConfig.isYtdlp()) {
			this.ytdlp = new YtdlpAudioSourceManager(ytdlpConfig.getPath(), ytdlpConfig.getSearchLimit(), ytdlpConfig.getCustomLoadArgs(), ytdlpConfig.getCustomPlaybackArgs());
		}

		if (lyricsSourcesConfig.isLrcLib()) {
			this.lrcLib = new LrcLibLyricsManager();
		}

		if (sourcesConfig.isJiosaavn()) {
			this.jioSaavn = new JioSaavnAudioSourceManager(jioSaavnConfig.buildConfig());

			proxyConfigurationService.configure(this.jioSaavn, jioSaavnConfig.getProxy());
		}

		if (sourcesConfig.isAudiomack()) {
			this.audiomack = new AudiomackAudioSourceManager(audiomackConfig.buildConfig());
		}

		if (sourcesConfig.isGaana()) {
			this.gaana = new GaanaAudioSourceManager(gaanaConfig.getApiUrl());
		}

		if (sourcesConfig.isShazam()) {
			this.shazam = new ShazamAudioSourceManager(
				pluginConfig.getProviders(),
				unused -> this.manager
			);
		}

		if (sourcesConfig.isPandora()) {
			this.pandora = new PandoraSourceManager(
				pluginConfig.getProviders(),
				unused -> this.manager
			);
			this.pandora.setCsrfToken(pandoraConfig.getCsrfToken());
			this.pandora.setAuthToken(pandoraConfig.getAuthToken());
			this.pandora.setRemoteTokenUrl(pandoraConfig.getRemoteTokenUrl());
		}

	}

	private boolean hasNewYoutubeSource() {
		try {
			Class.forName("dev.lavalink.youtube.YoutubeAudioSourceManager");
			return true;
		} catch (ClassNotFoundException ignored) {
			return false;
		}
	}

	@NotNull
	@Override
	public AudioPlayerManager configure(@NotNull AudioPlayerManager manager) {
		this.manager = manager;
		registerAudioSource("Spotify", this.spotify, this.sourcesConfig.isSpotify(), () -> manager.registerSourceManager(this.spotify));
		registerAudioSource("Amazon Music", this.amazonMusic, this.sourcesConfig.isAmazonMusic(), () -> manager.registerSourceManager(this.amazonMusic));
		registerAudioSource("Apple Music", this.appleMusic, this.sourcesConfig.isAppleMusic(), () -> manager.registerSourceManager(this.appleMusic));
		registerAudioSource("Deezer", this.deezer, this.sourcesConfig.isDeezer(), () -> manager.registerSourceManager(this.deezer));
		registerAudioSource("Yandex Music", this.yandexMusic, this.sourcesConfig.isYandexMusic(), () -> manager.registerSourceManager(this.yandexMusic));
		registerAudioSource("Flowery TTS", this.flowerytts, this.sourcesConfig.isFloweryTTS(), () -> manager.registerSourceManager(this.flowerytts));
		registerAudioSource("VK Music", this.vkMusic, this.sourcesConfig.isVkMusic(), () -> manager.registerSourceManager(this.vkMusic));
		registerAudioSource("Tidal", this.tidal, this.sourcesConfig.isTidal(), () -> manager.registerSourceManager(this.tidal));
		registerAudioSource("Qobuz", this.qobuz, this.sourcesConfig.isQobuz(), () -> manager.registerSourceManager(this.qobuz));
		registerAudioSource("YTDLP", this.ytdlp, this.sourcesConfig.isYtdlp(), () -> manager.registerSourceManager(this.ytdlp));
		registerAudioSource("JioSaavn", this.jioSaavn, this.sourcesConfig.isJiosaavn(), () -> manager.registerSourceManager(this.jioSaavn));
		registerAudioSource("Audiomack", this.audiomack, this.sourcesConfig.isAudiomack(), () -> manager.registerSourceManager(this.audiomack));
		registerAudioSource("Gaana", this.gaana, this.sourcesConfig.isGaana(), () -> manager.registerSourceManager(this.gaana));
		registerAudioSource("Shazam", this.shazam, this.sourcesConfig.isShazam(), () -> manager.registerSourceManager(this.shazam));
		registerAudioSource("Pandora", this.pandora, this.sourcesConfig.isPandora(), () -> manager.registerSourceManager(this.pandora));
		return manager;
	}

	@Override
	@NotNull
	public SearchManager configure(@NotNull SearchManager manager) {
		registerSearchSource("Spotify", this.spotify, this.sourcesConfig.isSpotify(), () -> manager.registerSearchManager(this.spotify));
		registerSearchSource("Amazon Music", this.amazonMusic, this.sourcesConfig.isAmazonMusic(), () -> manager.registerSearchManager(this.amazonMusic));
		registerSearchSource("Apple Music", this.appleMusic, this.sourcesConfig.isAppleMusic(), () -> manager.registerSearchManager(this.appleMusic));
		registerSearchSource("Deezer", this.deezer, this.sourcesConfig.isDeezer(), () -> manager.registerSearchManager(this.deezer));
		registerSearchSource("Youtube", this.youtube, this.sourcesConfig.isYoutube(), () -> manager.registerSearchManager(this.youtube));
		registerSearchSource("Yandex Music", this.yandexMusic, this.sourcesConfig.isYandexMusic(), () -> manager.registerSearchManager(this.yandexMusic));
		registerSearchSource("VK Music", this.vkMusic, this.sourcesConfig.isVkMusic(), () -> manager.registerSearchManager(this.vkMusic));
		registerSearchSource("JioSaavn", this.jioSaavn, this.sourcesConfig.isJiosaavn(), () -> manager.registerSearchManager(this.jioSaavn));
		registerSearchSource("Audiomack", this.audiomack, this.sourcesConfig.isAudiomack(), () -> manager.registerSearchManager(this.audiomack));
		registerSearchSource("Shazam", this.shazam, this.sourcesConfig.isShazam(), () -> manager.registerSearchManager(this.shazam));
		registerSearchSource("Pandora", this.pandora, this.sourcesConfig.isPandora(), () -> manager.registerSearchManager(this.pandora));
		return manager;
	}

	@NotNull
	@Override
	public LyricsManager configure(@NotNull LyricsManager manager) {
		registerLyricsSource("Deezer", this.deezer, this.lyricsSourcesConfig.isDeezer(), () -> manager.registerLyricsManager(this.deezer));
		registerLyricsSource("YouTube", this.youtube, this.lyricsSourcesConfig.isYoutube(), () -> manager.registerLyricsManager(this.youtube));
		registerLyricsSource("Yandex Music", this.yandexMusic, this.lyricsSourcesConfig.isYandexMusic(), () -> manager.registerLyricsManager(this.yandexMusic));
		registerLyricsSource("VK Music", this.vkMusic, this.lyricsSourcesConfig.isVkMusic(), () -> manager.registerLyricsManager(this.vkMusic));
		registerLyricsSource("LRCLIB", this.lrcLib, this.lyricsSourcesConfig.isLrcLib(), () -> manager.registerLyricsManager(this.lrcLib));
		return manager;
	}

	@PatchMapping("/v4/pulselink/config")
	public void updateConfig(@RequestBody Config config) {
		try {
			var spotifyConfig = config.getSpotify();
			if (spotifyConfig != null && this.spotify != null) {
				if (spotifyConfig.getApiUrl() != null) {
					this.spotify.setApiUrl(spotifyConfig.getApiUrl());
				}
				if (spotifyConfig.getAnonymousTokenUrl() != null) {
					this.spotify.setAnonymousTokenUrl(spotifyConfig.getAnonymousTokenUrl());
				}
				if (spotifyConfig.getPlaylistLoadLimit() != null && spotifyConfig.getPlaylistLoadLimit() > 0) {
					this.spotify.setPlaylistPageLimit(spotifyConfig.getPlaylistLoadLimit());
				}
				if (spotifyConfig.getAlbumLoadLimit() != null && spotifyConfig.getAlbumLoadLimit() > 0) {
					this.spotify.setAlbumPageLimit(spotifyConfig.getAlbumLoadLimit());
				}
			}

			var appleMusicConfig = config.getAppleMusic();
			if (appleMusicConfig != null && this.appleMusic != null && appleMusicConfig.getMediaAPIToken() != null) {
				this.appleMusic.setMediaAPIToken(appleMusicConfig.getMediaAPIToken());
			}

			var deezerConfig = config.getDeezer();
			if (deezerConfig != null && this.deezer != null) {
				if (deezerConfig.getArl() != null) {
					this.deezer.setArl(deezerConfig.getArl());
				}
				if (deezerConfig.getFormats() != null) {
					this.deezer.setFormats(deezerConfig.getFormats()
						.stream()
						.map(deezerTrackFormat -> DeezerAudioTrack.TrackFormat.from(deezerTrackFormat.name()))
						.toList()
						.toArray(new DeezerAudioTrack.TrackFormat[0]));
				}
			}

			var yandexMusicConfig = config.getYandexMusic();
			if (yandexMusicConfig != null && this.yandexMusic != null && yandexMusicConfig.getAccessToken() != null) {
				this.yandexMusic.setAccessToken(yandexMusicConfig.getAccessToken());
			}

			var vkMusicConfig = config.getVkMusic();
			if (vkMusicConfig != null && this.vkMusic != null && vkMusicConfig.getUserToken() != null) {
				this.vkMusic.setUserToken(vkMusicConfig.getUserToken());
			}

			var qobuzConfig = config.getQobuz();
			if (qobuzConfig != null && this.qobuz != null) {
				if (qobuzConfig.getUserOauthToken() != null) {
					this.qobuz.setUserOauthToken(qobuzConfig.getUserOauthToken());
				}
				if (qobuzConfig.getAppId() != null && qobuzConfig.getAppSecret() != null) {
					this.qobuz.setAppId(qobuzConfig.getAppId());
					this.qobuz.setAppSecret(qobuzConfig.getAppSecret());
				}
			}

			var ytdlpConfig = config.getYtdlp();
			if (ytdlpConfig != null && this.ytdlp != null) {
				if (ytdlpConfig.getPath() != null) {
					this.ytdlp.setPath(ytdlpConfig.getPath());
				}
				if (ytdlpConfig.getSearchLimit() != null && ytdlpConfig.getSearchLimit() > 0) {
					this.ytdlp.setSearchLimit(ytdlpConfig.getSearchLimit());
				}
				if (ytdlpConfig.getCustomLoadArgs() != null) {
					this.ytdlp.setCustomLoadArgs(ytdlpConfig.getCustomLoadArgs().toArray(String[]::new));
				}
				if (ytdlpConfig.getCustomPlaybackArgs() != null) {
					this.ytdlp.setCustomPlaybackArgs(ytdlpConfig.getCustomPlaybackArgs().toArray(String[]::new));
				}
			}

			var pandoraConfig = config.getPandora();
			if (pandoraConfig != null && this.pandora != null) {
				if (pandoraConfig.getCsrfToken() != null) {
					this.pandora.setCsrfToken(pandoraConfig.getCsrfToken());
				}
				if (pandoraConfig.getAuthToken() != null) {
					this.pandora.setAuthToken(pandoraConfig.getAuthToken());
				}
				if (pandoraConfig.getRemoteTokenUrl() != null) {
					this.pandora.setRemoteTokenUrl(pandoraConfig.getRemoteTokenUrl());
				}
			}

			log.info("PulseLink runtime config patch applied successfully.");
		} catch (RuntimeException exception) {
			log.error("PulseLink runtime config patch failed.", exception);
			throw exception;
		}
	}

	private void registerAudioSource(String name, Object component, boolean enabled, Runnable registration) {
		if (!enabled) {
			log.debug("Skipping {} audio source manager registration because the source is disabled.", name);
			return;
		}
		if (component == null) {
			log.warn("{} audio source manager was enabled but not initialized.", name);
			return;
		}
		log.info("Registering {} audio source manager...", name);
		registration.run();
	}

	private void registerSearchSource(String name, Object component, boolean enabled, Runnable registration) {
		if (!enabled) {
			log.debug("Skipping {} search manager registration because the source is disabled.", name);
			return;
		}
		if (component == null) {
			log.warn("{} search manager was enabled but not initialized.", name);
			return;
		}
		log.info("Registering {} search manager...", name);
		registration.run();
	}

	private void registerLyricsSource(String name, Object component, boolean enabled, Runnable registration) {
		if (!enabled) {
			log.debug("Skipping {} lyrics manager registration because the source is disabled.", name);
			return;
		}
		if (component == null) {
			log.warn("{} lyrics manager was enabled but not initialized.", name);
			return;
		}
		log.info("Registering {} lyrics manager...", name);
		registration.run();
	}
}
