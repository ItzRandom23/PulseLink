package com.github.itzrandom23.pulselink.soundcloud.hls;

import com.github.itzrandom23.pulselink.soundcloud.SoundCloudAudioSourceManager;
import com.github.itzrandom23.pulselink.soundcloud.hls.SoundCloudHlsPlaylistParser.HlsMediaPlaylist;
import com.github.itzrandom23.pulselink.soundcloud.hls.SoundCloudHlsPlaylistParser.HlsMasterPlaylist;
import com.github.itzrandom23.pulselink.soundcloud.hls.SoundCloudHlsPlaylistParser.HlsPlaylist;
import com.github.itzrandom23.pulselink.soundcloud.hls.SoundCloudHlsPlaylistParser.HlsRendition;
import com.github.itzrandom23.pulselink.soundcloud.hls.SoundCloudHlsPlaylistParser.HlsSegment;
import com.github.itzrandom23.pulselink.soundcloud.hls.SoundCloudHlsPlaylistParser.HlsVariant;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SoundCloudHlsHandler implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(SoundCloudHlsHandler.class);

	private static final int MAX_HISTORY = 200;
	private static final int MAX_GAP = 30;
	private static final int MASTER_REFRESH_INTERVAL = 3;
	private static final int LIVE_PRE_ROLL_SEGMENTS = 12;
	private static final int STUCK_THRESHOLD = 10;

	private final SoundCloudAudioSourceManager sourceManager;
	private final OutputStream output;
	private final String masterUrl;
	private String currentUrl;
	private double startTimeSeconds;

	private final Set<String> processedSegments = new HashSet<>();
	private final Deque<String> processedOrder = new ArrayDeque<>();
	private final Deque<HlsSegment> segmentQueue = new ArrayDeque<>();
	private boolean stop;
	private boolean isLive;
	private int lastMediaSequence = -1;
	private int highestSequence = -1;
	private int stuckCount;
	private boolean preRolled;
	private boolean justResynced;
	private int masterRefreshCounter;
	private String lastMapUri;

	public SoundCloudHlsHandler(SoundCloudAudioSourceManager sourceManager, OutputStream output, String masterUrl, double startTimeSeconds) {
		this.sourceManager = sourceManager;
		this.output = output;
		this.masterUrl = masterUrl;
		this.currentUrl = masterUrl;
		this.startTimeSeconds = startTimeSeconds;
	}

	public void stop() {
		this.stop = true;
	}

	@Override
	public void run() {
		try {
			playlistLoop();
		} catch (Exception e) {
			if (!stop) {
				log.error("SoundCloud HLS handler failed", e);
			}
		} finally {
			try {
				output.flush();
			} catch (IOException ignored) {
			}
			try {
				output.close();
			} catch (IOException ignored) {
			}
		}
	}

	private void playlistLoop() throws IOException {
		while (!stop) {
			try {
				String playlistContent = fetchText(currentUrl);
				if (playlistContent == null || playlistContent.isBlank()) {
					throw new IOException("Playlist fetch failed: empty response");
				}

				HlsPlaylist parsed = SoundCloudHlsPlaylistParser.parse(playlistContent, currentUrl);
				if (parsed instanceof HlsMasterPlaylist master) {
					handleMasterPlaylist(master);
					continue;
				}
				if (parsed instanceof HlsMediaPlaylist media) {
					handleMediaPlaylist(media);
					if (!isLive && segmentQueue.isEmpty()) {
						return;
					}
					if (isLive) {
						scheduleNextTick(media.getTargetDuration());
					}
				}
			} catch (IOException e) {
				if (!isLive || stop) {
					throw e;
				}
				log.warn("SoundCloud HLS playlist error (retrying): {}", e.getMessage());
				sleepMillis(3000);
			}
		}
	}

	private void handleMasterPlaylist(HlsMasterPlaylist master) throws IOException {
		List<HlsVariant> variants = new ArrayList<>(master.getVariants());
		variants.sort((a, b) -> Long.compare(b.getBandwidth(), a.getBandwidth()));

		HlsVariant best = variants.stream()
			.filter(v -> includesAudioCodec(v.getCodecs()) && !includesVideoCodec(v.getCodecs()))
			.findFirst()
			.orElseGet(() -> variants.stream().filter(v -> includesAudioCodec(v.getCodecs())).findFirst().orElse(null));

		if (best == null && !variants.isEmpty()) {
			best = variants.get(0);
		}

		if (best == null) {
			throw new IOException("No suitable variant found in master playlist");
		}

		String selectedUrl = best.getUrl();
		String audioGroup = best.getAudioGroup();
		if (audioGroup != null && master.getAudioGroups().containsKey(audioGroup)) {
			List<HlsRendition> group = master.getAudioGroups().get(audioGroup);
			HlsRendition rendition = group.stream().filter(HlsRendition::isDefault).findFirst()
				.orElseGet(() -> group.stream().filter(HlsRendition::isAutoSelect).findFirst().orElse(null));
			if (rendition == null && !group.isEmpty()) {
				rendition = group.get(0);
			}
			if (rendition != null && rendition.getUri() != null) {
				selectedUrl = rendition.getUri();
			}
		}

		this.currentUrl = selectedUrl;
	}

	private void handleMediaPlaylist(HlsMediaPlaylist media) throws IOException {
		this.isLive = media.isLive();

		if (startTimeSeconds > 0 && !isLive && processedSegments.isEmpty()) {
			skipForStartTime(media);
		}

		if (lastMediaSequence != -1 && (media.getMediaSequence() < lastMediaSequence
			|| media.getMediaSequence() > lastMediaSequence + MAX_GAP)) {
			if (isLive) {
				resetLiveState();
			}
		}
		lastMediaSequence = media.getMediaSequence();

		if (isLive && ++masterRefreshCounter >= MASTER_REFRESH_INTERVAL) {
			masterRefreshCounter = 0;
			currentUrl = masterUrl;
			return;
		}

		handleLivePreRoll(media);

		List<HlsSegment> newSegments = new ArrayList<>();
		for (HlsSegment segment : media.getSegments()) {
			if (segment.getSequence() != -1 && segment.getSequence() <= highestSequence) {
				continue;
			}
			String key = segmentKey(segment);
			if (!processedSegments.contains(key)) {
				newSegments.add(segment);
			}
		}

		if (!newSegments.isEmpty()) {
			stuckCount = 0;
			for (HlsSegment segment : newSegments) {
				if (segment.isDiscontinuity() && isLive) {
					resetLiveState();
					return;
				}

				rememberSegment(segmentKey(segment));
				segmentQueue.add(segment);
				if (segment.getSequence() != -1 && segment.getSequence() > highestSequence) {
					highestSequence = segment.getSequence();
				}
			}
		} else if (isLive && ++stuckCount >= STUCK_THRESHOLD) {
			stuckCount = 0;
			currentUrl = masterUrl;
			justResynced = true;
			return;
		}

		while (!segmentQueue.isEmpty() && !stop) {
			HlsSegment segment = segmentQueue.poll();
			if (segment == null) {
				break;
			}
			if (segment.getMap() != null && !Objects.equals(lastMapUri, segment.getMap().getUri())) {
				byte[] mapData = fetchBytes(segment.getMap().getUri());
				if (mapData != null && mapData.length > 0) {
					output.write(mapData);
				}
				lastMapUri = segment.getMap().getUri();
			}

			byte[] data = fetchBytes(segment.getUrl());
			if (data != null && data.length > 0) {
				output.write(data);
			}
			preRolled = true;
		}
	}

	private void skipForStartTime(HlsMediaPlaylist media) {
		double elapsed = 0;
		for (HlsSegment segment : media.getSegments()) {
			if (elapsed + segment.getDuration() <= startTimeSeconds) {
				elapsed += segment.getDuration();
				rememberSegment(segmentKey(segment));
				if (segment.getSequence() != -1 && segment.getSequence() > highestSequence) {
					highestSequence = segment.getSequence();
				}
			} else {
				break;
			}
		}
		startTimeSeconds = 0;
	}

	private void handleLivePreRoll(HlsMediaPlaylist media) {
		boolean isFirstLoad = processedSegments.isEmpty();
		if (isLive && (isFirstLoad || justResynced)) {
			if (justResynced) {
				processedSegments.clear();
				processedOrder.clear();
				highestSequence = -1;
			}
			int startIdx = Math.max(0, media.getSegments().size() - LIVE_PRE_ROLL_SEGMENTS);
			for (int i = 0; i < startIdx; i++) {
				HlsSegment segment = media.getSegments().get(i);
				rememberSegment(segmentKey(segment));
				if (segment.getSequence() != -1 && segment.getSequence() > highestSequence) {
					highestSequence = segment.getSequence();
				}
			}
			justResynced = false;
		} else {
			justResynced = false;
		}
	}

	private void resetLiveState() {
		segmentQueue.clear();
		processedSegments.clear();
		processedOrder.clear();
		highestSequence = -1;
		preRolled = false;
		justResynced = true;
	}

	private void rememberSegment(String key) {
		if (processedSegments.contains(key)) {
			return;
		}
		processedSegments.add(key);
		processedOrder.add(key);
		while (processedOrder.size() > MAX_HISTORY) {
			String oldest = processedOrder.poll();
			if (oldest != null) {
				processedSegments.remove(oldest);
			}
		}
	}

	private String segmentKey(HlsSegment segment) {
		return segment.getSequence() != -1 ? String.valueOf(segment.getSequence()) : segment.getUrl();
	}

	private boolean includesAudioCodec(String codecs) {
		if (codecs == null) return false;
		String lower = codecs.toLowerCase(Locale.ROOT);
		return lower.contains("mp4a") || lower.contains("opus");
	}

	private boolean includesVideoCodec(String codecs) {
		if (codecs == null) return false;
		return codecs.toLowerCase(Locale.ROOT).contains("avc1");
	}

	private void scheduleNextTick(int targetDuration) {
		double delaySeconds = Math.max(0.5, targetDuration / 2.0);
		sleepMillis((long) (delaySeconds * 1000));
	}

	private void sleepMillis(long millis) {
		if (millis <= 0 || stop) return;
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
	}

	private String fetchText(String url) throws IOException {
		try (HttpInterface httpInterface = sourceManager.getHttpInterface()) {
			try (CloseableHttpResponse response = httpInterface.execute(sourceManager.createRequest(url))) {
				if (response.getEntity() == null) {
					return null;
				}
				return IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
			}
		}
	}

	private byte[] fetchBytes(String url) throws IOException {
		IOException lastError = null;
		for (int attempt = 1; attempt <= 3; attempt++) {
			try (HttpInterface httpInterface = sourceManager.getHttpInterface()) {
				try (CloseableHttpResponse response = httpInterface.execute(sourceManager.createRequest(url))) {
					if (response.getEntity() == null) {
						return null;
					}
					return IOUtils.toByteArray(response.getEntity().getContent());
				}
			} catch (IOException e) {
				lastError = e;
				if (attempt < 3) {
					sleepMillis(250L * attempt);
				}
			}
		}
		throw lastError != null ? lastError : new IOException("Failed to fetch HLS segment");
	}
}
