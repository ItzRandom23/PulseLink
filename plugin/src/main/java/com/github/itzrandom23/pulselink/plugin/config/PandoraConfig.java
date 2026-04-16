package com.github.itzrandom23.pulselink.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.pulselink.pandora")
@Component
public class PandoraConfig {
	private static final String DEFAULT_REMOTE_TOKEN_URL = "https://get.1lucas1apk.fun/pandora/gettoken";

	private String csrfToken;
	private String authToken;
	private String remoteTokenUrl = DEFAULT_REMOTE_TOKEN_URL;

	public String getCsrfToken() {
		return this.csrfToken;
	}

	public void setCsrfToken(String csrfToken) {
		this.csrfToken = csrfToken;
	}

	public String getAuthToken() {
		return this.authToken;
	}

	public void setAuthToken(String authToken) {
		this.authToken = authToken;
	}

	public String getRemoteTokenUrl() {
		return this.remoteTokenUrl;
	}

	public void setRemoteTokenUrl(String remoteTokenUrl) {
		this.remoteTokenUrl = (remoteTokenUrl == null || remoteTokenUrl.isBlank())
			? DEFAULT_REMOTE_TOKEN_URL
			: remoteTokenUrl;
	}
}
