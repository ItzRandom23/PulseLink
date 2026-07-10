package com.github.itzrandom23.pulselink.shazam;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ShazamNextDataParser {

	private static final String NEXT_PUSH = "self.__next_f.push(";
	private static final Pattern SHAZAM_URL = Pattern.compile("https?://(?:www\\.)?shazam\\.com/(?:[a-z]{2}-[a-z]{2}/)?track/(\\d+)", Pattern.CASE_INSENSITIVE);

	List<Song> parse(String html) {
		String flightData = flightData(html);
		if (flightData.isBlank()) {
			return List.of();
		}

		Map<String, Song> songs = new LinkedHashMap<>();
		collect(flightData, "\"chartAppearances\"", true, songs);
		collect(flightData, "\"songData\"", false, songs);
		collect(flightData, "\"attributes\"", false, songs);
		return new ArrayList<>(songs.values());
	}

	private String flightData(String html) {
		StringBuilder data = new StringBuilder();
		int from = 0;
		while (true) {
			int marker = html.indexOf(NEXT_PUSH, from);
			if (marker < 0) {
				break;
			}
			int open = marker + NEXT_PUSH.length() - 1;
			int close = balancedEnd(html, open);
			if (close < 0) {
				from = open + 1;
				continue;
			}
			try {
				JsonBrowser push = JsonBrowser.parse(html.substring(open + 1, close));
				List<JsonBrowser> values = push.values();
				if (values.size() > 1 && !values.get(1).isNull()) {
					data.append(values.get(1).text().replace("\\\"", "\""));
				}
			} catch (IOException | RuntimeException ignored) {
				// Ignore individual malformed flight chunks; other chunks can still contain the page entity.
			}
			from = close + 1;
		}
		return data.toString();
	}

	private void collect(String data, String key, boolean unwrapSongData, Map<String, Song> songs) {
		int from = 0;
		while (true) {
			int keyIndex = data.indexOf(key, from);
			if (keyIndex < 0) {
				return;
			}
			int objectStart = enclosingObjectStart(data, keyIndex);
			int objectEnd = objectStart < 0 ? -1 : balancedEnd(data, objectStart);
			if (objectEnd < 0) {
				from = keyIndex + key.length();
				continue;
			}
			try {
				JsonBrowser object = JsonBrowser.parse(data.substring(objectStart, objectEnd + 1));
				JsonBrowser candidate = !object.get("songData").isNull() ? object.get("songData") : object;
				Song song = song(candidate, object);
				if (song != null) {
					merge(songs, song);
				}
			} catch (IOException | RuntimeException ignored) {
				// Flight data contains non-JSON protocol fragments between valid JSON objects.
			}
			from = objectEnd + 1;
		}
	}

	private Song song(JsonBrowser candidate, JsonBrowser context) {
		JsonBrowser attributes = candidate.get("attributes");
		if (attributes.isNull()) {
			attributes = candidate;
		}
		String title = attributes.get("name").safeText();
		String artist = attributes.get("artistName").safeText();
		long duration = attributes.get("durationInMillis").asLong(0L);
		String isrc = attributes.get("isrc").safeText();
		if (title.isBlank() || artist.isBlank()) {
			return null;
		}

		String url = attributes.get("url").safeText();
		String shazamId = firstNonBlank(candidate.get("shazamTrackId").safeText(), context.get("shazamTrackId").safeText(), idFromUrl(url));
		String appleMusicTrackId = firstNonBlank(candidate.get("id").safeText(), attributes.get("appleMusicTrackId").safeText());
		String artworkUrl = attributes.get("artwork").get("url").safeText().replace("{w}", "1000").replace("{h}", "1000");
		String album = attributes.get("albumName").safeText();
		String albumId = firstNonBlank(attributes.get("albumId").safeText(), context.get("albumId").safeText());
		int discNumber = (int) attributes.get("discNumber").asLong(0L);
		int trackNumber = (int) attributes.get("trackNumber").asLong(0L);
		int chartPosition = (int) firstNumber(context.get("position"), context.get("chartPosition"));
		return new Song(title, artist, duration, isrc, shazamId, appleMusicTrackId, url, artworkUrl, album, albumId, discNumber, trackNumber, chartPosition);
	}

	private void merge(Map<String, Song> songs, Song candidate) {
		String key = candidate.key();
		Song existing = songs.get(key);
		if (existing == null || candidate.completeness() > existing.completeness()) {
			songs.put(key, candidate);
		}
	}

	private int enclosingObjectStart(String input, int target) {
		ArrayDeque<Integer> objects = new ArrayDeque<>();
		boolean quoted = false;
		boolean escaped = false;
		for (int index = 0; index < target; index++) {
			char character = input.charAt(index);
			if (quoted) {
				if (escaped) escaped = false;
				else if (character == '\\') escaped = true;
				else if (character == '"') quoted = false;
				continue;
			}
			if (character == '"') quoted = true;
			else if (character == '{') objects.push(index);
			else if (character == '}' && !objects.isEmpty()) objects.pop();
		}
		return objects.isEmpty() ? -1 : objects.peek();
	}

	private int balancedEnd(String input, int start) {
		char opening = input.charAt(start);
		char closing = opening == '(' ? ')' : opening == '{' ? '}' : opening == '[' ? ']' : 0;
		if (closing == 0) return -1;
		int depth = 0;
		boolean quoted = false;
		boolean escaped = false;
		for (int index = start; index < input.length(); index++) {
			char character = input.charAt(index);
			if (quoted) {
				if (escaped) escaped = false;
				else if (character == '\\') escaped = true;
				else if (character == '"') quoted = false;
				continue;
			}
			if (character == '"') quoted = true;
			else if (character == opening) depth++;
			else if (character == closing && --depth == 0) return index;
		}
		return -1;
	}

	private String idFromUrl(String url) {
		Matcher matcher = SHAZAM_URL.matcher(url);
		return matcher.find() ? matcher.group(1) : "";
	}

	private String firstNonBlank(String... values) {
		for (String value : values) if (value != null && !value.isBlank()) return value;
		return "";
	}

	private long firstNumber(JsonBrowser... values) {
		for (JsonBrowser value : values) if (!value.isNull() && value.asLong(0L) > 0L) return value.asLong(0L);
		return 0L;
	}

	static final class Song {
		final String title, artist, isrc, shazamTrackId, appleMusicTrackId, url, artworkUrl, album, albumId;
		final long duration;
		final int discNumber, trackNumber, chartPosition;

		Song(String title, String artist, long duration, String isrc, String shazamTrackId, String appleMusicTrackId, String url, String artworkUrl, String album, String albumId, int discNumber, int trackNumber, int chartPosition) {
			this.title = title; this.artist = artist; this.duration = duration; this.isrc = isrc; this.shazamTrackId = shazamTrackId;
			this.appleMusicTrackId = appleMusicTrackId; this.url = url; this.artworkUrl = artworkUrl; this.album = album; this.albumId = albumId;
			this.discNumber = discNumber; this.trackNumber = trackNumber; this.chartPosition = chartPosition;
		}

		String key() { return firstNonEmpty(shazamTrackId, appleMusicTrackId, isrc, normalize(artist) + "\u0000" + normalize(title)); }
		int completeness() { return (shazamTrackId.isBlank() ? 0 : 1) + (isrc.isBlank() ? 0 : 1) + (duration > 0 ? 1 : 0) + (artworkUrl.isBlank() ? 0 : 1) + (album.isBlank() ? 0 : 1) + (chartPosition > 0 ? 1 : 0); }
		private static String firstNonEmpty(String... values) { for (String value : values) if (!value.isBlank()) return value; return ""; }
		private static String normalize(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim(); }
	}
}
