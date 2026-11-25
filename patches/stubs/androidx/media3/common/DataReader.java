package androidx.media3.common;

import java.io.IOException;

public interface DataReader {
    int read(byte[] buffer, int offset, int length) throws IOException;
}
