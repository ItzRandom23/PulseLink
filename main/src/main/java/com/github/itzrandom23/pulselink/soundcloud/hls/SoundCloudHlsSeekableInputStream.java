package com.github.itzrandom23.pulselink.soundcloud.hls;

import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoProvider;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class SoundCloudHlsSeekableInputStream extends SeekableInputStream {

	private final SoundCloudHlsStream stream;
	private long position;

	public SoundCloudHlsSeekableInputStream(SoundCloudHlsStream stream) {
		super(-1L, 0L);
		this.stream = stream;
	}

	@Override
	public long getPosition() {
		return position;
	}

	@Override
	protected void seekHard(long position) throws IOException {
		throw new IOException("SoundCloud HLS stream is not seekable");
	}

	@Override
	public boolean canSeekHard() {
		return false;
	}

	@Override
	public int read() throws IOException {
		int value = stream.read();
		if (value != -1) {
			position++;
		}
		return value;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		int read = stream.read(b, off, len);
		if (read > 0) {
			position += read;
		}
		return read;
	}

	@Override
	public long skip(long n) throws IOException {
		if (n <= 0) {
			return 0;
		}
		long remaining = n;
		byte[] buffer = new byte[4096];
		while (remaining > 0) {
			int toRead = (int) Math.min(buffer.length, remaining);
			int read = read(buffer, 0, toRead);
			if (read == -1) {
				break;
			}
			remaining -= read;
		}
		return n - remaining;
	}

	@Override
	public void close() throws IOException {
		stream.close();
	}

	@Override
	public List<AudioTrackInfoProvider> getTrackInfoProviders() {
		return Collections.emptyList();
	}
}
