package com.github.itzrandom23.pulselink.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.pulselink")
@Component
public class PulseLinkConfig {

	private String[] providers = new String[0];

	public String[] getProviders() {
		return this.providers;
	}

	public void setProviders(String[] providers) {
		this.providers = providers;
	}

}
