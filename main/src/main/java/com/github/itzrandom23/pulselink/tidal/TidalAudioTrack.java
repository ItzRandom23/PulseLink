package com.github.itzrandom23.pulselink.tidal;

import com.github.itzrandom23.pulselink.mirror.MirroringAudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TidalAudioTrack extends MirroringAudioTrack {

	private static final Logger log = LoggerFactory.getLogger(TidalAudioTrack.class);

	public TidalAudioTrack(AudioTrackInfo trackInfo, TidalSourceManager sourceManager) {
		this(trackInfo, null, null, null, null, sourceManager);
	}

	public TidalAudioTrack(AudioTrackInfo trackInfo, String albumName, String albumUrl, String artistUrl, String artistArtworkUrl, TidalSourceManager sourceManager) {
		super(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, null, false, sourceManager);
	}

	@Override
	protected InternalAudioTrack createAudioTrack(AudioTrackInfo trackInfo, SeekableInputStream stream) {
		throw new UnsupportedOperationException("Previews are not supported by Tidal");
	}

	@Override
	public void process(LocalAudioTrackExecutor executor) throws Exception {
		if (!this.isPreview) {
			var tidalSource = (TidalSourceManager) this.sourceManager;
			try (var httpInterface = tidalSource.getHttpInterface()) {
				var direct = tidalSource.getDirectStreamInfo(httpInterface, this.trackInfo.identifier);
				if (direct != null) {
					log.debug(
						"Using Tidal direct stream {} (quality={}, mimeType={})",
						direct.url(),
						direct.quality(),
						direct.mimeType()
					);
					try (var stream = new PersistentHttpStream(httpInterface, direct.url(), this.trackInfo.length)) {
						processDelegate(direct.format().createTrack(this.trackInfo, stream), executor);
						return;
					}
				}
			} catch (Exception e) {
				log.debug("Tidal direct stream failed for {}: {}", this.trackInfo.identifier, e.getMessage());
			}
		}

		super.process(executor);
	}

	@Override
	protected AudioTrack makeShallowClone() {
		return new TidalAudioTrack(this.trackInfo, (TidalSourceManager) this.sourceManager);
	}
}
