package com.github.itzrandom23.pulselink.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.pulselink.qobuz")
@Component
public class QobuzConfig {

	private String userOauthToken;
	private String appId;
	private String appSecret;

	public String getAppId() {
		return appId;
	}

	public void setAppId(String appId) {
		this.appId = appId;
	}

	public String getAppSecret() {
		return appSecret;
	}

	public void setAppSecret(String appSecret) {
		this.appSecret = appSecret;
	}

	public String getUserOauthToken() {
		return userOauthToken;
	}

	public void setUserOauthToken(String userOauthToken) {
		this.userOauthToken = userOauthToken;
	}

}
