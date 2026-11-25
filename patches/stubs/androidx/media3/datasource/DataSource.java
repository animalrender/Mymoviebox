package androidx.media3.datasource;

import android.net.Uri;
import androidx.media3.common.DataReader;

public interface DataSource extends DataReader {
    public interface Factory {
        DataSource createDataSource();
    }

    void addTransferListener(TransferListener transferListener);

    Uri getUri();
}
