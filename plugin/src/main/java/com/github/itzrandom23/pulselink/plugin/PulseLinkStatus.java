package com.github.itzrandom23.pulselink.plugin;

import java.util.List;
import java.util.Map;

public record PulseLinkStatus(
	boolean ready,
	Map<String, Boolean> enabledSources,
	Map<String, Boolean> enabledLyricsSources,
	List<String> providerTemplates,
	List<String> readinessIssues
) {
}
