package datadog.trace.instrumentation.servlet;

import datadog.trace.api.http.StoredCharBody;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.CharBuffer;

public class BufferedReaderWrapper extends BufferedReader {
  private final BufferedReader reader;
  private final StoredCharBody storedCharBody;

  public BufferedReaderWrapper(BufferedReader reader, StoredCharBody storedCharBody) {
    super(reader);
    this.reader = reader;
    this.storedCharBody = storedCharBody;
  }

  @Override
  public int read() throws IOException {
    int read = this.reader.read();
    if (read >= 0) {
      storedCharBody.appendData(read);
    } else {
      storedCharBody.maybeNotifyAndBlock();
    }
    return read;
  }

  @Override
  public int read(char[] cbuf, int off, int len) throws IOException {
    int read = this.reader.read(cbuf, off, len);
    if (read > 0) {
      storedCharBody.appendData(cbuf, off, off + read);
    } else if (read == -1) {
      storedCharBody.maybeNotifyAndBlock();
    }
    return read;
  }

  @Override
  public String readLine() throws IOException {
    String read = this.reader.readLine();
    if (read == null) {
      storedCharBody.maybeNotifyAndBlock();
      return null;
    }
    storedCharBody.appendData(read);
    storedCharBody.appendData('\n');
    return read;
  }

  @Override
  public long skip(long n) throws IOException {
    return this.reader.skip(n);
  }

  @Override
  public boolean ready() throws IOException {
    return this.reader.ready();
  }

  @Override
  public boolean markSupported() {
    return this.reader.markSupported();
  }

  @Override
  public void mark(int readAheadLimit) throws IOException {
    this.reader.mark(readAheadLimit);
  }

  @Override
  public void reset() throws IOException {
    this.reader.reset();
  }

  @Override
  public void close() throws IOException {
    this.reader.close();
    storedCharBody.maybeNotifyAndBlock();
  }

  @Override
  public int read(CharBuffer target) throws IOException {
    int initPos = target.position();
    int read = this.reader.read(target);
    if (read > 0) {
      int finalLimit = target.limit();
      int finalPos = target.position(); // or initPos + read
      target.limit(target.position());
      target.position(initPos);

      storedCharBody.appendData(target);

      target.limit(finalLimit);
      target.position(finalPos);
    } else if (read == -1) {
      storedCharBody.maybeNotifyAndBlock();
    }
    return read;
  }

  @Override
  public int read(char[] cbuf) throws IOException {
    int read = this.reader.read(cbuf);
    if (read > 0) {
      storedCharBody.appendData(cbuf, 0, read);
    } else if (read == -1) {
      storedCharBody.maybeNotifyAndBlock();
    }
    return read;
  }
}
