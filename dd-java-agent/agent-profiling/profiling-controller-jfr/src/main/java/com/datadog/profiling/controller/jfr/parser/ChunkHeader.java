package com.datadog.profiling.controller.jfr.parser;

import java.io.IOException;

/** A chunk header data object */
public final class ChunkHeader {
  static final byte[] MAGIC = new byte[] {'F', 'L', 'R', '\0'};
  public final short major;
  public final short minor;
  public final long size;
  public final long cpOffset;
  public final long metaOffset;
  public final long startNanos;
  public final long duration;
  public final long startTicks;
  public final long frequency;
  public final boolean compressed;

  ChunkHeader(RecordingStream recording) throws IOException {
    byte[] buffer = new byte[MAGIC.length];
    recording.read(buffer, 0, MAGIC.length);
    for (int i = 0; i < MAGIC.length; i++) {
      if (buffer[i] != MAGIC[i]) {
        throw new IOException(
            "Invalid JFR Magic Number: " + bytesToString(buffer, 0, MAGIC.length));
      }
    }
    major = recording.readShort();
    minor = recording.readShort();
    size = recording.readLong();
    cpOffset = recording.readLong();
    metaOffset = recording.readLong();
    startNanos = recording.readLong();
    duration = recording.readLong();
    startTicks = recording.readLong();
    frequency = recording.readLong();
    compressed = recording.readInt() != 0;
  }

  @Override
  public String toString() {
    return "ChunkHeader{"
        + "major="
        + major
        + ", minor="
        + minor
        + ", size="
        + size
        + ", cpOffset="
        + cpOffset
        + ", metaOffset="
        + metaOffset
        + ", startNanos="
        + startNanos
        + ", duration="
        + duration
        + ", startTicks="
        + startTicks
        + ", frequency="
        + frequency
        + ", compressed="
        + compressed
        + '}';
  }

  private static String bytesToString(byte[] array, int offset, int len) {
    StringBuilder sb = new StringBuilder("[");
    boolean comma = false;
    for (int i = 0; i < len; i++) {
      if (comma) {
        sb.append(", ");
      } else {
        comma = true;
      }
      sb.append(array[i + offset]);
    }
    sb.append(']');
    return sb.toString();
  }
}
