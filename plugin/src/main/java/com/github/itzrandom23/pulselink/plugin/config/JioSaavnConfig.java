package com.github.itzrandom23.pulselink.plugin.config;

import com.github.itzrandom23.pulselink.jiosaavn.JioSaavnAudioSourceManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.pulselink.jiosaavn")
@Component
public class JioSaavnConfig {
	@Nullable
	private HttpProxyConfig proxy;
	private JioSaavnDecryptionConfig decryption = new JioSaavnDecryptionConfig();

	@Nullable
	public HttpProxyConfig getProxy() {
		return this.proxy;
	}

	@SuppressWarnings("unused")
	public void setProxy(@Nullable HttpProxyConfig proxy) {
		this.proxy = proxy;
	}

	public JioSaavnDecryptionConfig getDecryption() {
		return decryption;
	}

	@SuppressWarnings("unused")
	public void setDecryption(JioSaavnDecryptionConfig decryption) {
		this.decryption = decryption;
	}

	public @NotNull JioSaavnAudioSourceManager.JioSaavnConfig buildConfig() {
		JioSaavnDecryptionConfig decryptionConfig = getDecryption() != null ? getDecryption() : new JioSaavnDecryptionConfig();
		if (decryptionConfig.getSecretKey() == null || decryptionConfig.getSecretKey().isBlank()) {
			decryptionConfig.setSecretKey("38346591");
		}
		if (decryptionConfig.getAlgorithm() == null || decryptionConfig.getAlgorithm().isBlank()) {
			decryptionConfig.setAlgorithm("DES");
		}
		if (decryptionConfig.getTransformation() == null || decryptionConfig.getTransformation().isBlank()) {
			decryptionConfig.setTransformation("DES/ECB/PKCS5Padding");
		}

		JioSaavnAudioSourceManager.JioSaavnConfig sourceConfig = new JioSaavnAudioSourceManager.JioSaavnConfig(decryptionConfig.getSecretKey());
		if (decryptionConfig.getAlgorithm() != null) {
			sourceConfig.setAlgorithm(decryptionConfig.getAlgorithm());
		}
		if (decryptionConfig.getTransformation() != null) {
			sourceConfig.setTransformation(decryptionConfig.getTransformation());
		}
		return sourceConfig;
	}
}
