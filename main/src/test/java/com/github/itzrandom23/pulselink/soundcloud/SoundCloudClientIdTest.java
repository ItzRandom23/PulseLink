package com.github.itzrandom23.pulselink.soundcloud;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicStatusLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SoundCloudClientIdTest {

	private static final String HOME = "https://soundcloud.com";
	private static final String SEARCH_PAGE = HOME + "/search";
	private static final String ASSET = "https://a-v2.sndcdn.com/assets/app_0.abc-123.js?version=1";
	private static final String OLD_ID = "A".repeat(32);
	private static final String NEW_ID = "B".repeat(32);
	private static final String RESULTS = """
		{"collection":[{"id":123,"title":"Nine Thou","duration":300000,
		"permalink_url":"https://soundcloud.com/example/nine-thou","user":{"username":"Styles Of Beyond"}}]}
		""";

	private final Map<String, Supplier<Reply>> routes = new HashMap<>();
	private final List<String> requests = new ArrayList<>();
	private final AtomicLong nanoTime = new AtomicLong();
	private HttpInterface http;
	private SoundCloudAudioSourceManager manager;

	@BeforeEach
	void setUp() throws Exception {
		http = mock(HttpInterface.class);
		when(http.execute(any(HttpGet.class))).thenAnswer(invocation -> {
			String url = ((HttpGet) invocation.getArgument(0)).getURI().toString();
			requests.add(url);
			Supplier<Reply> route = routes.get(url);
			assertNotNull(route, "Unexpected HTTP request: " + url);
			Reply reply = route.get();
			CloseableHttpResponse response = mock(CloseableHttpResponse.class);
			when(response.getStatusLine()).thenReturn(new BasicStatusLine(HttpVersion.HTTP_1_1, reply.status(), ""));
			when(response.getEntity()).thenReturn(new StringEntity(reply.body(), StandardCharsets.UTF_8));
			return response;
		});
	}

	@AfterEach
	void closeManager() {
		if (manager != null) manager.shutdown();
	}

	@Test
	void configuredClientIdSearchesWithoutFetchingTheBlockedWebsite() {
		createManager("  " + OLD_ID + "  ");
		reply(search(OLD_ID), 200, RESULTS);

		assertSearchResult();
		assertEquals(List.of(search(OLD_ID)), requests);
	}

	@Test
	void blockedHomepageFallsBackToSearchPageAndCachesTheDiscoveredId() throws Exception {
		createManager(null);
		reply(HOME, 403, "Forbidden");
		reply(SEARCH_PAGE, 200, "<script src=\"" + ASSET + "\"></script>");
		reply(ASSET, 200, "window.client_id=\"" + OLD_ID + "\";");
		reply(validation(OLD_ID), 200, "{}");
		reply(search(OLD_ID), 200, RESULTS);

		assertSearchResult();
		assertEquals(OLD_ID, manager.getClientId());
		assertEquals(List.of(HOME, SEARCH_PAGE, ASSET, validation(OLD_ID), search(OLD_ID)), requests);
	}

	@Test
	void discoveryFailuresAreSharedUntilTheCooldownExpiresAndThenCanRecover() throws Exception {
		createManager("");
		reply(HOME, 403, "Forbidden");
		reply(SEARCH_PAGE, 403, "Forbidden");

		FriendlyException failure = assertThrows(FriendlyException.class, manager::getClientId);
		assertEquals(FriendlyException.Severity.COMMON, failure.severity);
		assertTrue(failure.getMessage().contains("plugins.pulselink.soundcloud.clientId"));
		assertTrue(failure.getMessage().contains("proxy"));
		assertThrows(FriendlyException.class, manager::getClientId);
		nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(29));
		assertThrows(FriendlyException.class, manager::getClientId);
		assertEquals(List.of(HOME, SEARCH_PAGE), requests);

		nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(1));
		reply(HOME, 200, "{\"data\":{\"id\":\"" + NEW_ID + "\"}}");
		reply(validation(NEW_ID), 200, "{}");
		assertEquals(NEW_ID, manager.getClientId());
		assertEquals(NEW_ID, manager.getClientId());
		assertEquals(List.of(HOME, SEARCH_PAGE, HOME, validation(NEW_ID)), requests);
	}

	@Test
	void aBlockedAssetDoesNotPreventDiscoveryFromTheAlternatePage() throws Exception {
		createManager(null);
		reply(HOME, 200, "<script src=\"" + ASSET + "\"></script>");
		reply(ASSET, 403, "Forbidden");
		reply(SEARCH_PAGE, 200, "client_id=\"" + NEW_ID + "\"");
		reply(validation(NEW_ID), 200, "{}");

		assertEquals(NEW_ID, manager.getClientId());
		assertEquals(List.of(HOME, ASSET, SEARCH_PAGE, validation(NEW_ID)), requests);
	}

	@Test
	void triesEveryDistinctClientIdInAnAssetIncludingQuotedKeys() throws Exception {
		createManager(null);
		reply(HOME, 200, "<script src=\"" + ASSET + "\"></script>");
		reply(ASSET, 200, "{'client_id':'" + OLD_ID + "'}; {\"client_id\":\"" + OLD_ID +
			"\"}; {\"client_id\":\"" + NEW_ID + "\"}");
		reply(validation(OLD_ID), 403, "Invalid client ID");
		reply(validation(NEW_ID), 200, "{}");

		assertEquals(NEW_ID, manager.getClientId());
		assertEquals(List.of(HOME, ASSET, validation(OLD_ID), validation(NEW_ID)), requests);
	}

	@ParameterizedTest
	@ValueSource(ints = {429, 500, 503})
	void websiteRateLimitsAndServerErrorsEnterCooldownWithoutMoreRequests(int status) {
		createManager(null);
		reply(HOME, status, "Unavailable");

		assertThrows(FriendlyException.class, manager::getClientId);
		assertThrows(FriendlyException.class, manager::getClientId);
		assertEquals(List.of(HOME), requests);
	}

	@Test
	void validationRateLimitsStopDiscoveryRatherThanTryingMoreAssetsOrPages() {
		createManager(null);
		reply(HOME, 200, "client_id=\"" + OLD_ID + "\"");
		reply(validation(OLD_ID), 429, "Too many requests");

		assertThrows(FriendlyException.class, manager::getClientId);
		assertEquals(List.of(HOME, validation(OLD_ID)), requests);
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 403})
	void rejectedConfiguredIdsGiveAnActionableErrorWithoutDiscoveryOrRetry(int status) throws Exception {
		createManager(OLD_ID);
		reply(search(OLD_ID), status, "Rejected");

		FriendlyException failure = assertThrows(FriendlyException.class,
			() -> manager.loadItem(null, new AudioReference("scsearch:Nine Thou", null)));
		assertTrue(failure.getMessage().contains("configured client ID"));
		assertTrue(failure.getMessage().contains("HTTP " + status));
		assertFalse(failure.getMessage().contains(OLD_ID));
		assertEquals(OLD_ID, manager.getClientId());
		assertEquals(List.of(search(OLD_ID)), requests);
	}

	@Test
	void expiredAutomaticallyDiscoveredIdIsRefreshedAndTheSearchIsRetriedOnce() {
		createManager(null);
		reply(HOME, 200, "client_id=\"" + OLD_ID + "\"");
		reply(validation(OLD_ID), 200, "{}");
		routes.put(search(OLD_ID), () -> {
			reply(HOME, 200, "client_id=\"" + NEW_ID + "\"");
			return new Reply(401, "Expired");
		});
		reply(validation(NEW_ID), 200, "{}");
		reply(search(NEW_ID), 200, RESULTS);

		assertSearchResult();
		assertEquals(List.of(HOME, validation(OLD_ID), search(OLD_ID), HOME, validation(NEW_ID), search(NEW_ID)), requests);
	}

	@Test
	void apiRateLimitsDoNotTriggerClientIdDiscovery() {
		createManager(OLD_ID);
		reply(search(OLD_ID), 429, "Too many requests");

		assertThrows(RuntimeException.class,
			() -> manager.loadItem(null, new AudioReference("scsearch:Nine Thou", null)));
		assertEquals(List.of(search(OLD_ID)), requests);
	}

	private void createManager(String clientId) {
		var config = new SoundCloudAudioSourceManager.SoundCloudConfig();
		config.setClientId(clientId);
		manager = spy(new SoundCloudAudioSourceManager(config, unused -> null,
			(track, context) -> AudioReference.NO_TRACK, nanoTime::get));
		doReturn(http).when(manager).getHttpInterface();
	}

	private void assertSearchResult() {
		var item = manager.loadItem(null, new AudioReference("scsearch:Nine Thou", null));
		AudioPlaylist result = assertInstanceOf(AudioPlaylist.class, item);
		assertEquals(1, result.getTracks().size());
		assertEquals("Nine Thou", result.getTracks().get(0).getInfo().title);
	}

	private void reply(String url, int status, String body) {
		routes.put(url, () -> new Reply(status, body));
	}

	private static String search(String clientId) {
		return "https://api-v2.soundcloud.com/search/tracks?q=Nine+Thou&client_id=" + clientId + "&limit=10";
	}

	private static String validation(String clientId) {
		return "https://api-v2.soundcloud.com/search/tracks?q=test&limit=1&client_id=" + clientId;
	}

	private record Reply(int status, String body) {
	}
}
