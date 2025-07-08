package com.datadog.profiling.uploader;

import java.io.IOException;
import java.io.InputStream;

final class ByteCountingInputStream extends InputStream {
  private final InputStream source;
  private long readBytes = 0;
  private long mark = -1;

  ByteCountingInputStream(InputStream source) {
    this.source = source;
  }

  @Override
  public int read() throws IOException {
    int data = source.read();
    if (data >= 0) {
      readBytes++;
    }
    return data;
  }

  @Override
  public int read(byte[] b) throws IOException {
    return this.read(b, 0, b.length);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int read = source.read(b, off, len);
    if (read > 0) {
      readBytes += read;
    }
    return read;
  }

  @Override
  public long skip(long n) throws IOException {
    return source.skip(n);
  }

  @Override
  public int available() throws IOException {
    return source.available();
  }

  @Override
  public void close() throws IOException {
    source.close();
  }

  @Override
  public void mark(int readlimit) {
    mark = readBytes;
    source.mark(readlimit);
  }

  @Override
  public void reset() throws IOException {
    source.reset();
    if (mark > -1) {
      readBytes = mark;
      mark = -1;
    }
  }

  @Override
  public boolean markSupported() {
    return source.markSupported();
  }

  long getReadBytes() {
    return readBytes;
  }
}
