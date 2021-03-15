package com.datadog.profiling.uploader;

import java.io.IOException;
import java.io.OutputStream;

final class ByteCountingOutputStream extends OutputStream {
  private final OutputStream dest;
  private long writtenBytes = 0;

  ByteCountingOutputStream(OutputStream dest) {
    this.dest = dest;
  }

  @Override
  public void write(int i) throws IOException {
    dest.write(i);
    writtenBytes++;
  }

  @Override
  public void write(byte[] b) throws IOException {
    this.write(b, 0, b.length);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    dest.write(b, off, len);
    writtenBytes += len;
  }

  @Override
  public void flush() throws IOException {
    dest.flush();
  }

  @Override
  public void close() throws IOException {
    dest.close();
  }

  long getWrittenBytes() {
    return writtenBytes;
  }
}
