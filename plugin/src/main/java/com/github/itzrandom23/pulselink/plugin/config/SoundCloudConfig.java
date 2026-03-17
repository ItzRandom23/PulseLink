package com.github.itzrandom23.pulselink.plugin.config;

import com.github.itzrandom23.pulselink.soundcloud.SoundCloudAudioSourceManager;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.pulselink.soundcloud")
@Component
public class SoundCloudConfig {
	private String clientId;
	private int searchLimit = 10;
	private int playlistLoadLimit = 100;

	public String getClientId() {
		return clientId;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	public int getSearchLimit() {
		return searchLimit;
	}

	public void setSearchLimit(int searchLimit) {
		this.searchLimit = searchLimit;
	}

	public int getPlaylistLoadLimit() {
		return playlistLoadLimit;
	}

	public void setPlaylistLoadLimit(int playlistLoadLimit) {
		this.playlistLoadLimit = playlistLoadLimit;
	}

	public SoundCloudAudioSourceManager.SoundCloudConfig buildConfig() {
		var config = new SoundCloudAudioSourceManager.SoundCloudConfig();
		config.setClientId(this.clientId);
		config.setSearchLimit(this.searchLimit);
		config.setPlaylistLoadLimit(this.playlistLoadLimit);
		return config;
	}
}
