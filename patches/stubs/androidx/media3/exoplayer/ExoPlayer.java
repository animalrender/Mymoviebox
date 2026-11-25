package androidx.media3.exoplayer;

import androidx.media3.exoplayer.source.MediaSource;

public interface ExoPlayer {
    public static final class Builder {
        public Builder setMediaSourceFactory(MediaSource.Factory mediaSourceFactory) {
            return this;
        }
    }
}
