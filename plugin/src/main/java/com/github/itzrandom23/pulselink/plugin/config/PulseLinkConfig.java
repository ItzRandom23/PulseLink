package com.github.itzrandom23.pulselink.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import static com.github.itzrandom23.pulselink.mirror.MirroringAudioSourceManager.ISRC_PATTERN;
import static com.github.itzrandom23.pulselink.mirror.MirroringAudioSourceManager.QUERY_PATTERN;

@ConfigurationProperties(prefix = "plugins.pulselink")
@Component
public class PulseLinkConfig {

	private String[] providers = {
		"ytsearch:\"" + ISRC_PATTERN + "\"",
		"ytsearch:" + QUERY_PATTERN
	};

	public String[] getProviders() {
		return this.providers;
	}

	public void setProviders(String[] providers) {
		this.providers = providers;
	}

}
