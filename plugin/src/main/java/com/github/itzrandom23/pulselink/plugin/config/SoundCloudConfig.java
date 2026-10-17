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
	private String ffmpegPath = "ffmpeg";

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

	public String getFfmpegPath() {
		return this.ffmpegPath;
	}

	public void setFfmpegPath(String ffmpegPath) {
		this.ffmpegPath = ffmpegPath;
	}

	public @NotNull SoundCloudAudioSourceManager.SoundCloudConfig buildConfig() {
		var config = new SoundCloudAudioSourceManager.SoundCloudConfig();
		config.setSearchLimit(Math.max(1, this.searchLimit));
		config.setUserTrackLimit(Math.max(1, this.userTrackLimit));
		if (this.ffmpegPath != null && !this.ffmpegPath.isBlank()) {
			config.setFfmpegPath(this.ffmpegPath);
		}
		return config;
	}
}
