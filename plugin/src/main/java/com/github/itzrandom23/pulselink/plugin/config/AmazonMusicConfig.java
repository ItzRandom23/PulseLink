package com.github.itzrandom23.pulselink.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.pulselink.amazonmusic")
@Component
public class AmazonMusicConfig {

	private int searchLimit = 10;

	public int getSearchLimit() {
		return this.searchLimit;
	}

	public void setSearchLimit(int searchLimit) {
		this.searchLimit = searchLimit;
	}
}
