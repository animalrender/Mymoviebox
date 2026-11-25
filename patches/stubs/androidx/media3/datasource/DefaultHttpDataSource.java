package androidx.media3.datasource;

import android.net.Uri;

public class DefaultHttpDataSource implements HttpDataSource {
    public static final class Factory implements HttpDataSource.Factory {
        public Factory() {}

        public Factory setAllowCrossProtocolRedirects(boolean allowCrossProtocolRedirects) {
            return this;
        }

        public Factory setTransferListener(TransferListener transferListener) {
            return this;
        }

        public HttpDataSource createDataSource() {
            return null;
        }
    }

    public void addTransferListener(TransferListener transferListener) {}

    public Uri getUri() {
        return null;
    }

    public int read(byte[] buffer, int offset, int length) {
        return -1;
    }
}
