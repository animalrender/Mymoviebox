package androidx.media3.datasource;

import android.net.Uri;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

    public long open(DataSpec dataSpec) throws IOException {
        return -1L;
    }

    public Uri getUri() {
        return null;
    }

    public Map<String, List<String>> getResponseHeaders() {
        return Collections.emptyMap();
    }

    public int read(byte[] buffer, int offset, int length) throws IOException {
        return -1;
    }

    public void close() throws IOException {}
}
