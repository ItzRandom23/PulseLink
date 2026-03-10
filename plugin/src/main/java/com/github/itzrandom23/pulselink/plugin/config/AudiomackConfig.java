package com.github.itzrandom23.pulselink.plugin.config;

import com.github.itzrandom23.pulselink.audiomack.AudiomackAudioSourceManager;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.pulselink.audiomack")
@Component
public class AudiomackConfig {
	private int searchLimit = 10;
	private int artistTrackLimit = 25;

	public int getSearchLimit() {
		return searchLimit;
	}

	public void setSearchLimit(int searchLimit) {
		this.searchLimit = searchLimit;
	}

	public int getArtistTrackLimit() {
		return artistTrackLimit;
	}

	public void setArtistTrackLimit(int artistTrackLimit) {
		this.artistTrackLimit = artistTrackLimit;
	}

	public AudiomackAudioSourceManager.AudiomackConfig buildConfig() {
		var config = new AudiomackAudioSourceManager.AudiomackConfig();
		config.setSearchLimit(this.searchLimit);
		config.setArtistTrackLimit(this.artistTrackLimit);
		return config;
	}
}
