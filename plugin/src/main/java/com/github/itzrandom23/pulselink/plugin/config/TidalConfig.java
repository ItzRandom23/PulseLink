package com.github.itzrandom23.pulselink.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@ConfigurationProperties(prefix = "plugins.pulselink.tidal")
@Component
public class TidalConfig {
	private static final String DEFAULT_TOKEN = "i4ZDjcyhed7Mu47q";

	private String countryCode;
	private int searchLimit;
	private String token = DEFAULT_TOKEN;
	private List<String> hifiApis;
	private List<String> hifiQualities;

	public String getCountryCode() {
		return this.countryCode;
	}

	public void setCountryCode(String countryCode) {
		this.countryCode = countryCode;
	}

	public int getSearchLimit() {
		return this.searchLimit;
	}

	public void setSearchLimit(int searchLimit) {
		this.searchLimit = searchLimit;
	}

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token;
	}

	public List<String> getHifiApis() {
		return this.hifiApis;
	}

	public void setHifiApis(List<String> hifiApis) {
		this.hifiApis = hifiApis;
	}

	public List<String> getHifiQualities() {
		return this.hifiQualities;
	}

	public void setHifiQualities(List<String> hifiQualities) {
		this.hifiQualities = hifiQualities;
	}
}
