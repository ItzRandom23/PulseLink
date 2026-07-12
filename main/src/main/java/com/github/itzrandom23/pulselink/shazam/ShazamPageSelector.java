package com.github.itzrandom23.pulselink.shazam;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class ShazamPageSelector {

	private ShazamPageSelector() {
	}

	static List<ShazamNextDataParser.Song> select(ShazamUrl page, List<ShazamNextDataParser.Song> songs) {
		return switch (page.route) {
			case TRACK -> selectTrack(page, songs);
			case ARTIST -> selectArtist(page, songs);
			case ALBUM -> selectAlbum(page, songs);
			case CHART -> selectChart(songs);
			case RADIOSPINS -> songs;
			case UNSUPPORTED -> List.of();
		};
	}

	private static List<ShazamNextDataParser.Song> selectTrack(ShazamUrl page, List<ShazamNextDataParser.Song> songs) {
		for (ShazamNextDataParser.Song song : songs) {
			if (page.id.equals(song.shazamTrackId)) {
				return List.of(song);
			}
		}
		for (ShazamNextDataParser.Song song : songs) {
			if (song.url.contains("/track/" + page.id)) {
				return List.of(song);
			}
		}
		String slug = normalize(page.slug == null ? "" : page.slug);
		List<ShazamNextDataParser.Song> selected = songs.stream().filter(song -> normalize(song.title).equals(slug)).limit(1).toList();
		return selected.isEmpty() && !songs.isEmpty() ? List.of(songs.get(0)) : selected;
	}

	private static List<ShazamNextDataParser.Song> selectArtist(ShazamUrl page, List<ShazamNextDataParser.Song> songs) {
		String artist = normalize(page.slug == null ? "" : page.slug.replace('-', ' ').replace('_', ' '));
		List<ShazamNextDataParser.Song> selected = songs.stream().filter(song -> normalize(song.artist).equals(artist)).toList();
		return selected.isEmpty() ? songs : selected;
	}

	private static List<ShazamNextDataParser.Song> selectAlbum(ShazamUrl page, List<ShazamNextDataParser.Song> songs) {
		List<ShazamNextDataParser.Song> selected = songs.stream().filter(song -> page.id.equals(song.albumId))
			.sorted(Comparator.comparingInt((ShazamNextDataParser.Song song) -> song.discNumber).thenComparingInt(song -> song.trackNumber))
			.toList();
		return selected.isEmpty() ? songs : selected;
	}

	private static List<ShazamNextDataParser.Song> selectChart(List<ShazamNextDataParser.Song> songs) {
		List<ShazamNextDataParser.Song> selected = songs.stream().filter(song -> song.chartPosition > 0).sorted(Comparator.comparingInt(song -> song.chartPosition)).toList();
		return selected.isEmpty() ? songs : selected;
	}

	private static String normalize(String value) {
		return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
	}
}
