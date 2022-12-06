package com.datadog.profiling.controller.jfr.parser;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

final class RecordingStream implements AutoCloseable {
  private final DataInputStream delegate;
  private long position = 0;

  RecordingStream(InputStream is) {
    BufferedInputStream bis =
        (is instanceof BufferedInputStream)
            ? (BufferedInputStream) is
            : new BufferedInputStream(is);
    delegate = new DataInputStream(bis);
  }

  long position() {
    return position;
  }

  void read(byte[] buffer, int offset, int length) throws IOException {
    while (length > 0) {
      int read = delegate.read(buffer, offset, length);
      if (read == -1) {
        throw new IOException("Unexpected EOF");
      }
      offset += read;
      length -= read;
      position += read;
    }
  }

  byte read() throws IOException {
    position += 1;
    return delegate.readByte();
  }

  short readShort() throws IOException {
    position += 2;
    return delegate.readShort();
  }

  int readInt() throws IOException {
    position += 4;
    return delegate.readInt();
  }

  long readLong() throws IOException {
    position += 8;
    return delegate.readLong();
  }

  long readVarint() throws IOException {
    long value = 0;
    int readValue = 0;
    int i = 0;
    do {
      readValue = delegate.read();
      value |= (long) (readValue & 0x7F) << (7 * i);
      i++;
    } while ((readValue & 0x80) != 0
        // In fact a fully LEB128 encoded 64bit number could take up to 10 bytes
        // (in order to store 64 bit original value using 7bit slots we need at most 10 of them).
        // However, eg. JMC parser will stop at 9 bytes, assuming that the compressed number is
        // a Java unsigned long (therefore having only 63 bits and they all fit in 9 bytes).
        && i < 9);
    position += i;
    return value;
  }

  int available() throws IOException {
    return delegate.available();
  }

  void skip(long bytes) throws IOException {
    long toSkip = bytes;
    while (toSkip > 0) {
      toSkip -= delegate.skip(toSkip);
    }
    position += bytes;
  }

  public void mark(int readlimit) {
    delegate.mark(readlimit);
  }

  public void reset() throws IOException {
    delegate.reset();
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }
}
