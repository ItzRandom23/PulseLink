package com.github.itzrandom23.pulselink.plugin.config;

import com.github.itzrandom23.pulselink.soundcloud.SoundCloudAudioSourceManager;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.pulselink.soundcloud")
@Component
public class SoundCloudConfig {

	private int searchLimit = 10;
	private int userTrackLimit = 25;

	public int getSearchLimit() {
		return this.searchLimit;
	}

	public void setSearchLimit(int searchLimit) {
		this.searchLimit = searchLimit;
	}

	public int getUserTrackLimit() {
		return this.userTrackLimit;
	}

	public void setUserTrackLimit(int userTrackLimit) {
		this.userTrackLimit = userTrackLimit;
	}

	public @NotNull SoundCloudAudioSourceManager.SoundCloudConfig buildConfig() {
		var config = new SoundCloudAudioSourceManager.SoundCloudConfig();
		config.setSearchLimit(Math.max(1, this.searchLimit));
		config.setUserTrackLimit(Math.max(1, this.userTrackLimit));
		return config;
	}
}
