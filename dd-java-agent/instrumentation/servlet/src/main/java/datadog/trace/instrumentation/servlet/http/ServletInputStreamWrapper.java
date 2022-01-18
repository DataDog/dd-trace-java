package datadog.trace.instrumentation.servlet.http;

import datadog.trace.api.http.StoredByteBody;
import java.io.IOException;
import javax.servlet.ServletInputStream;

public class ServletInputStreamWrapper extends ServletInputStream {
  public final ServletInputStream is;
  final StoredByteBody storedByteBody;

  public ServletInputStreamWrapper(ServletInputStream is, StoredByteBody storedByteBody) {
    this.is = is;
    this.storedByteBody = storedByteBody;
  }

  @Override
  public int read(byte[] b) throws IOException {
    int numRead = is.read(b);
    if (numRead > 0) {
      storedByteBody.appendData(b, 0, numRead);
    } else if (numRead == -1) {
      storedByteBody.maybeNotify();
    }
    return numRead;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int numRead = is.read(b, off, len);
    if (numRead > 0) {
      storedByteBody.appendData(b, off, off + numRead);
    } else if (numRead == -1) {
      storedByteBody.maybeNotify();
    }
    return numRead;
  }

  @Override
  public int read() throws IOException {
    int read = is.read();
    if (read == -1) {
      storedByteBody.maybeNotify();
    }
    storedByteBody.appendData(read);
    return read;
  }

  @Override
  public int readLine(byte[] b, int off, int len) throws IOException {
    int numRead = is.readLine(b, off, len);
    if (numRead > 0) {
      storedByteBody.appendData(b, off, off + numRead);
    } else if (numRead == -1) {
      storedByteBody.maybeNotify();
    }

    return numRead;
  }

  @Override
  public long skip(long n) throws IOException {
    return is.skip(n);
  }

  @Override
  public int available() throws IOException {
    return is.available();
  }

  @Override
  public void close() throws IOException {
    is.close();
    storedByteBody.maybeNotify();
  }

  @Override
  public void mark(int readlimit) {
    is.mark(readlimit);
  }

  @Override
  public void reset() throws IOException {
    is.reset();
  }

  @Override
  public boolean markSupported() {
    return is.markSupported();
  }
}
