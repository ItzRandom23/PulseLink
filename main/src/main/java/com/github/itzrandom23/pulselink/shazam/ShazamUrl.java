package com.github.itzrandom23.pulselink.shazam;

import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ShazamUrl {

	enum Route {
		TRACK,
		ARTIST,
		ALBUM,
		CHART,
		RADIOSPINS,
		UNSUPPORTED
	}

	final String originalUrl;
	final String normalizedUrl;
	final Route route;
	final String locale;
	final String id;
	final String slug;

	private ShazamUrl(String originalUrl, String normalizedUrl, Route route, String locale, String id, String slug) {
		this.originalUrl = originalUrl;
		this.normalizedUrl = normalizedUrl;
		this.route = route;
		this.locale = locale;
		this.id = id;
		this.slug = slug;
	}

	@Nullable
	static ShazamUrl parse(String input) {
		try {
			URI uri = URI.create(input);
			String host = uri.getHost();
			if (host == null || !(host.equalsIgnoreCase("shazam.com") || host.equalsIgnoreCase("www.shazam.com"))) {
				return null;
			}

			List<String> parts = new ArrayList<>();
			for (String part : uri.getPath().split("/")) {
				if (!part.isBlank()) {
					parts.add(part);
				}
			}
			if (parts.isEmpty()) {
				return unsupported(input, uri);
			}

			int index = 0;
			String locale = "en-us";
			if (parts.get(0).matches("(?i)[a-z]{2}-[a-z]{2}")) {
				locale = parts.get(0).toLowerCase(Locale.ROOT);
				index++;
			}
			if (index >= parts.size()) {
				return unsupported(input, uri);
			}

			String kind = parts.get(index).toLowerCase(Locale.ROOT);
			Route route = switch (kind) {
				case "track", "song" -> Route.TRACK;
				case "artist" -> Route.ARTIST;
				case "album" -> Route.ALBUM;
				case "charts" -> Route.CHART;
				case "radiospins" -> Route.RADIOSPINS;
				default -> Route.UNSUPPORTED;
			};
			if (route == Route.UNSUPPORTED) {
				return unsupported(input, uri);
			}

			String id = null;
			String slug = null;
			if (route == Route.RADIOSPINS) {
				return new ShazamUrl(input, "https://www.shazam.com/radiospins", route, locale, "radiospins", "top-200-on-radio");
			}
			if (route == Route.CHART) {
				if (index + 2 >= parts.size()) {
					return unsupported(input, uri);
				}
				id = parts.get(index + 1);
				slug = parts.get(index + 2);
			} else {
				for (int partIndex = index + 1; partIndex < parts.size(); partIndex++) {
					if (parts.get(partIndex).matches("\\d+")) {
						id = parts.get(partIndex);
						if (route == Route.ARTIST && partIndex > index + 1) {
							slug = parts.get(partIndex - 1);
						} else if (partIndex + 1 < parts.size()) {
							slug = parts.get(partIndex + 1);
						}
						break;
					}
				}
			}
			if (id == null) {
				return unsupported(input, uri);
			}

			// Keep the original route spelling and segment order. Shazam artist URLs
			// are /artist/{slug}/{id}, while albums and songs are /{type}/{id}/{slug}.
			String canonicalKind = kind;
			StringBuilder path = new StringBuilder("/");
			if (!locale.equals("en-us") || index > 0) {
				path.append(locale).append('/');
			}
			path.append(canonicalKind).append('/');
			if (route == Route.CHART) {
				path.append(id).append('/').append(slug);
			} else if (route == Route.ARTIST) {
				if (slug != null) path.append(slug).append('/');
				path.append(id);
			} else {
				path.append(id);
				if (slug != null) {
					path.append('/').append(slug);
				}
			}
			return new ShazamUrl(input, "https://www.shazam.com" + path, route, locale, id, slug);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static ShazamUrl unsupported(String originalUrl, URI uri) {
		return new ShazamUrl(originalUrl, uri.getScheme() + "://" + uri.getAuthority() + uri.getPath(), Route.UNSUPPORTED, "en-us", null, null);
	}
}
