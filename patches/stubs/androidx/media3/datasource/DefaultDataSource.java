package androidx.media3.datasource;

import android.content.Context;
import android.net.Uri;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class DefaultDataSource implements DataSource {
    public static final class Factory implements DataSource.Factory {
        public Factory(Context context) {}

        public Factory(Context context, DataSource.Factory baseDataSourceFactory) {}

        public Factory setTransferListener(TransferListener transferListener) {
            return this;
        }

        public DefaultDataSource createDataSource() {
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
