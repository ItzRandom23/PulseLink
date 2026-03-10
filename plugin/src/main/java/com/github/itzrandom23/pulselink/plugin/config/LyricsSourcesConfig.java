package com.github.itzrandom23.pulselink.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


@ConfigurationProperties(prefix = "plugins.pulselink.lyrics-sources")
@Component
public class LyricsSourcesConfig {

	private boolean deezer = false;
	private boolean youtube = false;
	private boolean yandexMusic = false;
	private boolean vkMusic = false;
	private boolean lrcLib = false;

	public boolean isDeezer() {
		return this.deezer;
	}

	public void setDeezer(boolean deezer) {
		this.deezer = deezer;
	}

	public boolean isYoutube() {
		return this.youtube;
	}

	public void setYoutube(boolean youtube) {
		this.youtube = youtube;
	}

	public boolean isYandexMusic() {
		return this.yandexMusic;
	}

	public void setYandexMusic(boolean yandexMusic) {
		this.yandexMusic = yandexMusic;
	}

	public boolean isVkMusic() {
		return this.vkMusic;
	}

	public void setVkMusic(boolean vkMusic) {
		this.vkMusic = vkMusic;
	}

	public boolean isLrcLib() {
		return this.lrcLib;
	}

	public void setLrcLib(boolean lrcLib) {
		this.lrcLib = lrcLib;
	}
}
