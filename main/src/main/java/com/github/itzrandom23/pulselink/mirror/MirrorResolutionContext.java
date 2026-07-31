package com.github.itzrandom23.pulselink.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Per-track-resolution context carried through all mirror and delegate operations to:
 * - enforce a strict maximum mirror depth.
 * - detect and stop provider and identifier cycles within one resolution pass.
 * - apply an overall deadline to the complete mirror chain.
 */
public class MirrorResolutionContext {

	public static final int DEFAULT_MAX_MIRROR_DEPTH = 2;
	public static final Duration DEFAULT_RESOLUTION_TIMEOUT = Duration.ofSeconds(30);

	public final String correlationId;
	public final String originalProvider;
	public final String originalIdentifier;
	public final int maxMirrorDepth;
	public final Instant deadline;

	public int mirrorDepth;
	public String currentProvider;
	public String currentIdentifier;
	public final Set<String> attemptedProviders = new LinkedHashSet<>();
	public final Set<String> attemptedTracks = new LinkedHashSet<>();

	public MirrorResolutionContext(String originalProvider, String originalIdentifier) {
		this(originalProvider, originalIdentifier, DEFAULT_MAX_MIRROR_DEPTH,
			Instant.now().plus(DEFAULT_RESOLUTION_TIMEOUT));
	}

	public MirrorResolutionContext(String originalProvider, String originalIdentifier,
	                                int maxMirrorDepth, Instant deadline) {
		this.correlationId = UUID.randomUUID().toString();
		this.originalProvider = originalProvider;
		this.originalIdentifier = originalIdentifier;
		this.maxMirrorDepth = maxMirrorDepth;
		this.deadline = deadline;
		this.mirrorDepth = 0;
		this.currentProvider = originalProvider;
		this.currentIdentifier = originalIdentifier;
	}

	public synchronized boolean pushMirror(String nextProvider, String nextIdentifier) {
		if (this.mirrorDepth >= this.maxMirrorDepth) {
			return false;
		}
		this.mirrorDepth++;
		this.currentProvider = nextProvider;
		this.currentIdentifier = nextIdentifier;
		return true;
	}

	public boolean hasProviderBeenAttempted(String normalizedProviderKey) {
		return this.attemptedProviders.contains(normalizedProviderKey);
	}

	public void markProviderAttempted(String key) {
		this.attemptedProviders.add(key);
	}

	public boolean hasTrackBeenProcessed(String normalizedTrackKey) {
		return this.attemptedTracks.contains(normalizedTrackKey);
	}

	public void markTrackProcessed(String key) {
		this.attemptedTracks.add(key);
	}

	public boolean isDeadlineExceeded() {
		return Instant.now().isAfter(this.deadline);
	}

	@Override
	public String toString() {
		return "MirrorResolutionContext{cid=" + correlationId +
			", origProvider=" + originalProvider +
			", origIdent=" + originalIdentifier +
			", depth=" + mirrorDepth + "/" + maxMirrorDepth +
			", providersTried=" + attemptedProviders +
			", tracksTried=" + attemptedTracks +
			'}';
	}
}