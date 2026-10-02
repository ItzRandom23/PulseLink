package com.github.itzrandom23.pulselink.soundcloud;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.HttpVersion;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SoundCloudAudioSourceManagerTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "PULSELINK_SOUNDCLOUD_LIVE_TEST", matches = "true")
    void reportedPlaylistLoadsWithinClientTimeout() throws Exception {
        var source = new SoundCloudAudioSourceManager(
            new SoundCloudAudioSourceManager.SoundCloudConfig(), ignored -> null, null);
        try {
            var result = assertTimeoutPreemptively(java.time.Duration.ofSeconds(15), () -> source.loadItem(null,
                new AudioReference("https://soundcloud.com/kandicookiecallyx/sets/musica-musica-musica", null)));
            assertInstanceOf(AudioPlaylist.class, result);
            var playlist = (AudioPlaylist) result;
            assertFalse(playlist.getTracks().isEmpty());
            assertTrue(playlist.getTracks().stream().allMatch(t -> !"Unknown title".equals(t.getInfo().title)));
            System.out.println("Live SoundCloud playlist loaded: " + playlist.getTracks().size() + " tracks");
        } finally {
            source.shutdown();
        }
    }
    @Test void sparsePlaylistEntriesAreHydratedInBatchesAndKeepPlaylistOrder() throws Exception {
        var source = source();
        var http = mock(HttpInterface.class);
        doReturn(http).when(source).getHttpInterface();
        doReturn("fresh").when(source).getClientId();
        var playlist = response(200, "{\"kind\":\"playlist\",\"tracks\":[{\"id\":2},{\"id\":1},{\"id\":3}]}");
        var batch = response(200, "[{\"id\":1,\"title\":\"One\",\"duration\":1000},{\"id\":2,\"title\":\"Two\",\"duration\":2000}]");
        when(http.execute(any(HttpUriRequest.class))).thenReturn(playlist, batch);
        var result = (AudioPlaylist) source.loadItem(null, new AudioReference("https://soundcloud.com/artist/sets/playlist", null));
        assertEquals(List.of("Two", "One"), result.getTracks().stream().map(t -> t.getInfo().title).toList());
        var requests = ArgumentCaptor.forClass(HttpUriRequest.class);
        verify(http, times(2)).execute(requests.capture());
        assertEquals("ids=2,1,3&client_id=fresh", requests.getAllValues().get(1).getURI().getQuery());
        verify(source, never()).getStreamInfo(any(), anyString());
    }
    @Test void playlistMetadataDoesNotProbeEveryTrackStream() throws Exception {
        var source = source();
        var http = mock(HttpInterface.class);
        doReturn(http).when(source).getHttpInterface();
        doReturn("fresh").when(source).getClientId();
        var tracks = new java.util.ArrayList<String>();
        for (int i = 0; i < 72; i++) {
            tracks.add("{\"id\":" + i + ",\"title\":\"Song " + i + "\",\"duration\":180000}");
        }
        var playlist = response(200, "{\"kind\":\"playlist\",\"title\":\"Test playlist\",\"tracks\":[" + String.join(",", tracks) + "]}");
        when(http.execute(any(HttpUriRequest.class))).thenReturn(playlist);
        var result = source.loadItem(null, new AudioReference("https://soundcloud.com/artist/sets/playlist", null));
        assertInstanceOf(AudioPlaylist.class, result);
        assertEquals(72, ((AudioPlaylist) result).getTracks().size());
        assertEquals(180000, ((AudioPlaylist) result).getTracks().get(0).getDuration());
        verify(http, times(1)).execute(any(HttpUriRequest.class));
        verify(source, never()).getStreamInfo(any(), anyString());
    }
    private SoundCloudAudioSourceManager source() {
        return spy(new SoundCloudAudioSourceManager(
            new SoundCloudAudioSourceManager.SoundCloudConfig(), ignored -> null, null));
    }

    private CloseableHttpResponse response(int status, String body) {
        var response = mock(CloseableHttpResponse.class);
        when(response.getStatusLine()).thenReturn(new BasicStatusLine(HttpVersion.HTTP_1_1, status, "test"));
        when(response.getEntity()).thenReturn(new StringEntity(body, java.nio.charset.StandardCharsets.UTF_8));
        return response;
    }

    @Test void refreshesRejectedClientIdAndRetriesOriginalPlaylist() throws Exception {
        var source = source();
        var http = mock(HttpInterface.class);
        doReturn("fresh").when(source).getClientId();
        var rejected = response(401, "");
        var success = response(200, "{\"kind\":\"playlist\"}");
        when(http.execute(any(HttpUriRequest.class))).thenReturn(rejected, success);
        var result = source.getJson(http, "https://api-v2.soundcloud.com/resolve?url=playlist&client_id=stale");
        assertEquals("playlist", result.get("kind").text());
        var requests = ArgumentCaptor.forClass(HttpUriRequest.class);
        verify(http, times(2)).execute(requests.capture());
        assertEquals("url=playlist&client_id=fresh", requests.getAllValues().get(1).getURI().getQuery());
    }

    @Test void authenticationRetryIsBounded() throws Exception {
        var source = source();
        var http = mock(HttpInterface.class);
        doReturn("fresh").when(source).getClientId();
        when(http.execute(any(HttpUriRequest.class))).thenAnswer(ignored -> response(401, ""));
        assertThrows(IOException.class, () -> source.getJson(http, "https://api-v2.soundcloud.com/resolve?client_id=stale"));
        verify(http, times(2)).execute(any(HttpUriRequest.class));
    }

    @Test void rateLimitsDoNotRefreshAuthentication() throws Exception {
        var source = source();
        var http = mock(HttpInterface.class);
        var limited = response(429, "");
        when(http.execute(any(HttpUriRequest.class))).thenReturn(limited);
        assertThrows(IOException.class, () -> source.getJson(http, "https://api-v2.soundcloud.com/resolve?client_id=stale"));
        verify(source, never()).getClientId();
        verify(http).execute(any(HttpUriRequest.class));
    }

    @Test void discoversValidClientIdInsteadOfCachingRejectedHtmlCandidate() throws Exception {
        var source = source();
        var http = mock(HttpInterface.class);
        doReturn(http).when(source).getHttpInterface();
        doReturn("client_id:\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\" https://a-v2.sndcdn.com/assets/app-123.js")
            .when(source).fetchText("https://soundcloud.com");
        doReturn("client_id:\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"")
            .when(source).fetchText("https://a-v2.sndcdn.com/assets/app-123.js");
        var rejected = response(401, "");
        var success = response(200, "{}");
        when(http.execute(any(HttpUriRequest.class))).thenReturn(rejected, success);
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", source.getClientId());
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", source.getClientId());
        verify(http, times(2)).execute(any(HttpUriRequest.class));
    }

    @Test void mediaPlaylistDoesNotTreatFirstSegmentAsAnotherManifest() throws Exception {
        var source = source();
        var http = mock(HttpInterface.class);
        String url = "https://media.example/playlist.m3u8";
        doReturn("#EXTM3U\n#EXTINF:4\nfirst.aac\n#EXTINF:4\nsecond.aac\n#EXT-X-ENDLIST")
            .when(source).fetchText(http, url);
        assertEquals(List.of("https://media.example/first.aac", "https://media.example/second.aac"), source.resolveHlsParts(http, url));
        verify(source, never()).fetchText(http, "https://media.example/first.aac");
    }
}
