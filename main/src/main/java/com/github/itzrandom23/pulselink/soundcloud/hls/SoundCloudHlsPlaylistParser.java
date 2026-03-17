package com.github.itzrandom23.pulselink.soundcloud.hls;

import java.net.URI;
import java.util.*;

public final class SoundCloudHlsPlaylistParser {

	private SoundCloudHlsPlaylistParser() {
	}

	public static HlsPlaylist parse(String content, String baseUrl) {
		if (content == null) {
			throw new IllegalArgumentException("Playlist content is null");
		}

		List<HlsVariant> variants = new ArrayList<>();
		Map<String, List<HlsRendition>> audioGroups = new HashMap<>();

		List<HlsSegment> segments = new ArrayList<>();
		int mediaSequence = 0;
		int targetDuration = 0;
		boolean endList = false;

		double pendingDuration = -1;
		boolean pendingDiscontinuity = false;
		HlsMap pendingMap = null;
		boolean sawMaster = false;
		Map<String, String> pendingVariantAttributes = null;

		String[] lines = content.split("\n");
		int segmentIndex = 0;
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i].trim();
			if (line.isEmpty()) {
				continue;
			}

			if (line.startsWith("#EXT-X-STREAM-INF")) {
				sawMaster = true;
				pendingVariantAttributes = parseAttributes(line);
				continue;
			}

			if (line.startsWith("#EXT-X-MEDIA")) {
				Map<String, String> attrs = parseAttributes(line);
				String type = attrs.get("TYPE");
				if (type != null && type.equalsIgnoreCase("AUDIO")) {
					String groupId = stripQuotes(attrs.get("GROUP-ID"));
					if (groupId != null) {
						String uri = stripQuotes(attrs.get("URI"));
						String def = attrs.getOrDefault("DEFAULT", "NO");
						String auto = attrs.getOrDefault("AUTOSELECT", "NO");
						HlsRendition rendition = new HlsRendition(uri, "YES".equalsIgnoreCase(def), "YES".equalsIgnoreCase(auto));
						audioGroups.computeIfAbsent(groupId, key -> new ArrayList<>()).add(rendition);
					}
				}
				continue;
			}

			if (line.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
				mediaSequence = parseIntAfter(line, ':', 0);
				continue;
			}

			if (line.startsWith("#EXT-X-TARGETDURATION")) {
				targetDuration = parseIntAfter(line, ':', 0);
				continue;
			}

			if (line.startsWith("#EXTINF")) {
				pendingDuration = parseDoubleAfter(line, ':', -1);
				continue;
			}

			if (line.startsWith("#EXT-X-MAP")) {
				Map<String, String> attrs = parseAttributes(line);
				String uri = stripQuotes(attrs.get("URI"));
				if (uri != null) {
					pendingMap = new HlsMap(resolve(baseUrl, uri));
				}
				continue;
			}

			if (line.startsWith("#EXT-X-DISCONTINUITY")) {
				pendingDiscontinuity = true;
				continue;
			}

			if (line.startsWith("#EXT-X-ENDLIST")) {
				endList = true;
				continue;
			}

			if (line.startsWith("#")) {
				continue;
			}

			if (pendingVariantAttributes != null) {
				String url = resolve(baseUrl, line);
				long bandwidth = parseLongSafe(pendingVariantAttributes.get("BANDWIDTH"), 0L);
				String codecs = stripQuotes(pendingVariantAttributes.get("CODECS"));
				String audio = stripQuotes(pendingVariantAttributes.get("AUDIO"));
				variants.add(new HlsVariant(url, bandwidth, codecs, audio));
				pendingVariantAttributes = null;
				continue;
			}

			String segmentUrl = resolve(baseUrl, line);
			int sequence = mediaSequence + segmentIndex;
			segmentIndex++;
			double duration = pendingDuration >= 0 ? pendingDuration : 0;
			segments.add(new HlsSegment(segmentUrl, duration, sequence, pendingDiscontinuity, pendingMap));
			pendingDuration = -1;
			pendingDiscontinuity = false;
		}

		if (sawMaster) {
			return new HlsMasterPlaylist(variants, audioGroups);
		}

		return new HlsMediaPlaylist(segments, mediaSequence, targetDuration, !endList);
	}

	private static Map<String, String> parseAttributes(String line) {
		int idx = line.indexOf(':');
		String payload = idx >= 0 ? line.substring(idx + 1) : "";
		Map<String, String> result = new HashMap<>();
		StringBuilder current = new StringBuilder();
		boolean inQuotes = false;
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < payload.length(); i++) {
			char c = payload.charAt(i);
			if (c == '"') {
				inQuotes = !inQuotes;
			}
			if (c == ',' && !inQuotes) {
				parts.add(current.toString());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		if (current.length() > 0) {
			parts.add(current.toString());
		}

		for (String part : parts) {
			int eq = part.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = part.substring(0, eq).trim();
			String value = part.substring(eq + 1).trim();
			result.put(key, value);
		}
		return result;
	}

	private static String stripQuotes(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
			return trimmed.substring(1, trimmed.length() - 1);
		}
		return trimmed;
	}

	private static int parseIntAfter(String line, char delim, int defaultValue) {
		int idx = line.indexOf(delim);
		if (idx < 0 || idx + 1 >= line.length()) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(line.substring(idx + 1).trim());
		} catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}

	private static double parseDoubleAfter(String line, char delim, double defaultValue) {
		int idx = line.indexOf(delim);
		if (idx < 0 || idx + 1 >= line.length()) {
			return defaultValue;
		}
		try {
			String value = line.substring(idx + 1).trim();
			int comma = value.indexOf(',');
			if (comma >= 0) {
				value = value.substring(0, comma);
			}
			return Double.parseDouble(value);
		} catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}

	private static long parseLongSafe(String value, long defaultValue) {
		if (value == null) {
			return defaultValue;
		}
		try {
			return Long.parseLong(stripQuotes(value));
		} catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}

	private static String resolve(String base, String path) {
		try {
			return URI.create(base).resolve(path).toString();
		} catch (IllegalArgumentException ignored) {
			return path;
		}
	}

	public interface HlsPlaylist {
	}

	public static final class HlsMasterPlaylist implements HlsPlaylist {
		private final List<HlsVariant> variants;
		private final Map<String, List<HlsRendition>> audioGroups;

		public HlsMasterPlaylist(List<HlsVariant> variants, Map<String, List<HlsRendition>> audioGroups) {
			this.variants = variants;
			this.audioGroups = audioGroups;
		}

		public List<HlsVariant> getVariants() {
			return variants;
		}

		public Map<String, List<HlsRendition>> getAudioGroups() {
			return audioGroups;
		}
	}

	public static final class HlsMediaPlaylist implements HlsPlaylist {
		private final List<HlsSegment> segments;
		private final int mediaSequence;
		private final int targetDuration;
		private final boolean live;

		public HlsMediaPlaylist(List<HlsSegment> segments, int mediaSequence, int targetDuration, boolean live) {
			this.segments = segments;
			this.mediaSequence = mediaSequence;
			this.targetDuration = targetDuration;
			this.live = live;
		}

		public List<HlsSegment> getSegments() {
			return segments;
		}

		public int getMediaSequence() {
			return mediaSequence;
		}

		public int getTargetDuration() {
			return targetDuration;
		}

		public boolean isLive() {
			return live;
		}
	}

	public static final class HlsVariant {
		private final String url;
		private final long bandwidth;
		private final String codecs;
		private final String audioGroup;

		public HlsVariant(String url, long bandwidth, String codecs, String audioGroup) {
			this.url = url;
			this.bandwidth = bandwidth;
			this.codecs = codecs;
			this.audioGroup = audioGroup;
		}

		public String getUrl() {
			return url;
		}

		public long getBandwidth() {
			return bandwidth;
		}

		public String getCodecs() {
			return codecs;
		}

		public String getAudioGroup() {
			return audioGroup;
		}
	}

	public static final class HlsRendition {
		private final String uri;
		private final boolean isDefault;
		private final boolean autoSelect;

		public HlsRendition(String uri, boolean isDefault, boolean autoSelect) {
			this.uri = uri;
			this.isDefault = isDefault;
			this.autoSelect = autoSelect;
		}

		public String getUri() {
			return uri;
		}

		public boolean isDefault() {
			return isDefault;
		}

		public boolean isAutoSelect() {
			return autoSelect;
		}
	}

	public static final class HlsMap {
		private final String uri;

		public HlsMap(String uri) {
			this.uri = uri;
		}

		public String getUri() {
			return uri;
		}
	}

	public static final class HlsSegment {
		private final String url;
		private final double duration;
		private final int sequence;
		private final boolean discontinuity;
		private final HlsMap map;

		public HlsSegment(String url, double duration, int sequence, boolean discontinuity, HlsMap map) {
			this.url = url;
			this.duration = duration;
			this.sequence = sequence;
			this.discontinuity = discontinuity;
			this.map = map;
		}

		public String getUrl() {
			return url;
		}

		public double getDuration() {
			return duration;
		}

		public int getSequence() {
			return sequence;
		}

		public boolean isDiscontinuity() {
			return discontinuity;
		}

		public HlsMap getMap() {
			return map;
		}
	}
}
