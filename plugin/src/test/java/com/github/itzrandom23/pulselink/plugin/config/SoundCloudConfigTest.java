package com.github.itzrandom23.pulselink.plugin.config;

import com.github.itzrandom23.pulselink.plugin.PulseLinkPlugin;
import com.github.itzrandom23.pulselink.plugin.service.ProxyConfigurationService;
import com.github.itzrandom23.pulselink.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SoundCloudConfigTest {

	@Test
	void bindsTheClientIdAndProxyAndPassesTheIdToTheSourceManagerConfig() {
		String clientId = "A".repeat(32);
		var properties = new MapConfigurationPropertySource(Map.of(
			"plugins.pulselink.soundcloud.client-id", clientId,
			"plugins.pulselink.soundcloud.proxy.url", "http://localhost:3128",
			"plugins.pulselink.soundcloud.proxy.username", "proxy-user",
			"plugins.pulselink.soundcloud.proxy.password", "proxy-password"
		));
		SoundCloudConfig config = new Binder(properties)
			.bind("plugins.pulselink.soundcloud", Bindable.of(SoundCloudConfig.class)).get();

		assertEquals(clientId, config.buildConfig().getClientId());
		assertNotNull(config.getProxy());
		assertEquals("http://localhost:3128", config.getProxy().getUrl());
		assertEquals("proxy-user", config.getProxy().getUsername());
		assertEquals("proxy-password", config.getProxy().getPassword());
	}

	@Test
	void aBlankClientIdPreservesAutomaticDiscovery() {
		var config = new SoundCloudConfig();
		config.setClientId("  ");
		assertNull(config.buildConfig().getClientId());
	}

	@Test
	void rejectsMalformedClientIdsBeforeMakingRequests() {
		var config = new SoundCloudConfig();
		config.setClientId("not-a-soundcloud-web-client-id");
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, config::buildConfig);
		assertTrue(failure.getMessage().contains("32 alphanumeric characters"));
	}

	@Test
	void appliesTheConfiguredProxyAndRegistersSoundCloudWhenEnabled() {
		var sources = new SourcesConfig();
		sources.setSoundcloud(true);
		var config = new SoundCloudConfig();
		var proxy = new HttpProxyConfig();
		proxy.setUrl("http://localhost:3128");
		config.setProxy(proxy);
		var proxyService = mock(ProxyConfigurationService.class);
		var plugin = createPlugin(sources, config, proxyService);

		var captor = ArgumentCaptor.forClass(HttpConfigurable.class);
		verify(proxyService).configure(captor.capture(), same(proxy));
		var source = assertInstanceOf(SoundCloudAudioSourceManager.class, captor.getValue());
		try {
			var manager = mock(AudioPlayerManager.class);
			plugin.configure(manager);
			verify(manager).registerSourceManager(source);
		} finally {
			source.shutdown();
		}
	}

	@Test
	void aProxyDoesNotImplicitlyEnableSoundCloud() {
		var proxyService = mock(ProxyConfigurationService.class);
		var config = new SoundCloudConfig();
		var proxy = new HttpProxyConfig();
		proxy.setUrl("http://localhost:3128");
		config.setProxy(proxy);

		createPlugin(new SourcesConfig(), config, proxyService);
		verifyNoInteractions(proxyService);
	}

	private PulseLinkPlugin createPlugin(SourcesConfig sources, SoundCloudConfig soundCloud,
	                                    ProxyConfigurationService proxyService) {
		return new PulseLinkPlugin(new PulseLinkConfig(), sources, new LyricsSourcesConfig(),
			new SpotifyConfig(), new AppleMusicConfig(), new DeezerConfig(), new YandexMusicConfig(),
			new FloweryTTSConfig(), new VkMusicConfig(), new TidalConfig(), new JioSaavnConfig(),
			new AudiomackConfig(), new GaanaConfig(), soundCloud, new PandoraConfig(), proxyService);
	}
}
