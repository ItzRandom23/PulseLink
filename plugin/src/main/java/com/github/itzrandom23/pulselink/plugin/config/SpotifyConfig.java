package com.github.itzrandom23.pulselink.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.pulselink.spotify")
@Component
public class SpotifyConfig {

    private String apiUrl;
    private String countryCode = "US";
    private int playlistLoadLimit = 6;
    private int albumLoadLimit = 6;
    private boolean resolveArtistsInSearch = true;
    private boolean localFiles = false;

    public String getApiUrl() {
        return this.apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getCountryCode() {
        return this.countryCode;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
    }

    public int getPlaylistLoadLimit() {
        return this.playlistLoadLimit;
    }

    public void setPlaylistLoadLimit(int playlistLoadLimit) {
        this.playlistLoadLimit = playlistLoadLimit;
    }

    public int getAlbumLoadLimit() {
        return this.albumLoadLimit;
    }

    public void setAlbumLoadLimit(int albumLoadLimit) {
        this.albumLoadLimit = albumLoadLimit;
    }

    public boolean isResolveArtistsInSearch() {
        return this.resolveArtistsInSearch;
    }

    public void setResolveArtistsInSearch(boolean resolveArtistsInSearch) {
        this.resolveArtistsInSearch = resolveArtistsInSearch;
    }

    public boolean isLocalFiles() {
        return this.localFiles;
    }

    public void setLocalFiles(boolean localFiles) {
        this.localFiles = localFiles;
    }
}
