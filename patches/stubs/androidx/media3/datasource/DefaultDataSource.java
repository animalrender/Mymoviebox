package androidx.media3.datasource;

import android.content.Context;
import android.net.Uri;

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

    public Uri getUri() {
        return null;
    }

    public int read(byte[] buffer, int offset, int length) {
        return -1;
    }
}
