package com.github.itzrandom23.pulselink.plugin.config;

public class JioSaavnDecryptionConfig {

	private static final String DEFAULT_ALGORITHM = "DES";
	private static final String DEFAULT_TRANSFORMATION = "DES/ECB/PKCS5Padding";
	private static final String DEFAULT_SECRET_KEY = "38346591";

	private String algorithm = DEFAULT_ALGORITHM;
	private String transformation = DEFAULT_TRANSFORMATION;
	private String secretKey = DEFAULT_SECRET_KEY;

	public JioSaavnDecryptionConfig(String algorithm, String transformation, String secretKey) {
		this.algorithm = algorithm;
		this.transformation = transformation;
		this.secretKey = secretKey;
	}

	public JioSaavnDecryptionConfig(String secretKey) {
		this.secretKey = secretKey;
	}

	public JioSaavnDecryptionConfig() {
	}

	public String getAlgorithm() {
		return algorithm;
	}

	public void setAlgorithm(String algorithm) {
		this.algorithm = algorithm;
	}

	public String getTransformation() {
		return transformation;
	}

	public void setTransformation(String transformation) {
		this.transformation = transformation;
	}

	public String getSecretKey() {
		return secretKey;
	}

	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

}
