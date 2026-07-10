package com.github.itzrandom23.pulselink.shazam;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

final class ShazamHtmlClient {

	private static final Logger log = LoggerFactory.getLogger(ShazamHtmlClient.class);
	private static final int MAX_REDIRECTS = 5;
	private static final int MAX_ATTEMPTS = 3;
	private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

	private final HttpInterface httpInterface;

	ShazamHtmlClient(HttpInterface httpInterface) {
		this.httpInterface = httpInterface;
	}

	String get(ShazamUrl page) throws IOException {
		IOException lastFailure = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return get(page.normalizedUrl, page.route.name().toLowerCase(), 0);
			} catch (FriendlyException e) {
				if (!e.getMessage().contains("HTTP 5") || attempt == MAX_ATTEMPTS) {
					throw e;
				}
				lastFailure = new IOException(e);
			} catch (IOException e) {
				lastFailure = e;
				if (attempt == MAX_ATTEMPTS) {
					throw e;
				}
			}
		}
		if (lastFailure != null) {
			throw lastFailure;
		}
		throw failure("a temporary network error", page.route.name().toLowerCase(), null);
	}

	private String get(String url, String pageType, int redirectCount) throws IOException {
		if (redirectCount > MAX_REDIRECTS) {
			throw failure("too many redirects", pageType, null);
		}

		HttpGet request = new HttpGet(url);
		setBrowserHeaders(request);
		log.debug("GET Shazam {} metadata: {}", pageType, url);
		try (var response = httpInterface.execute(request)) {
			int statusCode = response.getStatusLine().getStatusCode();
			if (statusCode >= 300 && statusCode < 400) {
				var location = response.getFirstHeader("Location");
				if (location == null || location.getValue() == null || location.getValue().isBlank()) {
					throw failure("HTTP " + statusCode + " without a redirect location", pageType, null);
				}
				String redirectUrl = URI.create(url).resolve(location.getValue()).toString();
				log.debug("Shazam {} metadata redirect: {} -> {}", pageType, url, redirectUrl);
				return get(redirectUrl, pageType, redirectCount + 1);
			}
			if (statusCode != 200) {
				log.debug("GET Shazam {} metadata returned HTTP {}: {}", pageType, statusCode, url);
				throw failure("HTTP " + statusCode, pageType, null);
			}
			if (response.getEntity() == null) {
				throw failure("an empty response", pageType, null);
			}

			var contentType = response.getEntity().getContentType();
			if (contentType == null || !contentType.getValue().toLowerCase().contains("text/html")) {
				throw failure("a non-HTML response", pageType, null);
			}
			String html = readBounded(response.getEntity().getContent());
			if (html.isBlank()) {
				throw failure("an empty response", pageType, null);
			}
			log.debug("Shazam {} metadata loaded with HTTP 200: {}", pageType, url);
			return html;
		}
	}

	private void setBrowserHeaders(HttpGet request) {
		request.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
		request.setHeader("Accept-Language", "en-US,en;q=0.9");
		request.setHeader("Cache-Control", "max-age=0");
		request.setHeader("Upgrade-Insecure-Requests", "1");
		request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36");
		request.setHeader("Sec-Fetch-Dest", "document");
		request.setHeader("Sec-Fetch-Mode", "navigate");
		request.setHeader("Sec-Fetch-Site", "none");
		request.setHeader("Sec-Fetch-User", "?1");
		request.setHeader("Cookie", "geoip_country=IN");
	}

	private String readBounded(java.io.InputStream input) throws IOException {
		try (input; var output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) != -1) {
				if (output.size() + read > MAX_RESPONSE_BYTES) {
					throw new FriendlyException("Shazam page response exceeded the maximum allowed size.", FriendlyException.Severity.SUSPICIOUS, null);
				}
				output.write(buffer, 0, read);
			}
			return output.toString(StandardCharsets.UTF_8);
		}
	}

	private FriendlyException failure(String reason, String pageType, Throwable cause) {
		return new FriendlyException("Shazam page returned " + reason + " while loading " + pageType + " metadata.", FriendlyException.Severity.SUSPICIOUS, cause);
	}
}
