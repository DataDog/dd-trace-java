package com.datadog.profiling.controller.jfr.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JFR Chunk metadata
 *
 * <p>It contains the chunk specific type specifications
 */
public final class MetadataEvent {
  private static final byte[] COMMON_BUFFER = new byte[4096]; // reusable byte buffer

  public final int size;
  public final long startTime;
  public final long duration;
  public final long metadataId;

  MetadataEvent(RecordingStream stream) throws IOException {
    size = (int) stream.readVarint();
    long typeId = stream.readVarint();
    if (typeId != 0) {
      throw new IOException("Unexpected event type: " + typeId + " (should be 0)");
    }
    startTime = stream.readVarint();
    duration = stream.readVarint();
    metadataId = stream.readVarint();
    readElements(stream, readStringTable(stream));
  }

  private String[] readStringTable(RecordingStream stream) throws IOException {
    int stringCnt = (int) stream.readVarint();
    String[] stringConstants = new String[stringCnt];
    for (int stringIdx = 0; stringIdx < stringCnt; stringIdx++) {
      stringConstants[stringIdx] = readUTF8(stream);
    }
    return stringConstants;
  }

  private void readElements(RecordingStream stream, String[] stringConstants) throws IOException {
    // get the element name
    int stringPtr = (int) stream.readVarint();
    boolean isClassElement = "class".equals(stringConstants[stringPtr]);

    // process the attributes
    int attrCount = (int) stream.readVarint();
    String superType = null;
    String name = null;
    String id = null;
    for (int i = 0; i < attrCount; i++) {
      int keyPtr = (int) stream.readVarint();
      int valPtr = (int) stream.readVarint();
      // ignore anything but 'class' elements
      if (isClassElement) {
        if ("superType".equals(stringConstants[keyPtr])) {
          superType = stringConstants[valPtr];
        } else if ("name".equals(stringConstants[keyPtr])) {
          name = stringConstants[valPtr];
        } else if ("id".equals(stringConstants[keyPtr])) {
          id = stringConstants[valPtr];
        }
      }
    }
    // now inspect all the enclosed elements
    int elemCount = (int) stream.readVarint();
    for (int i = 0; i < elemCount; i++) {
      readElements(stream, stringConstants);
    }
  }

  private String readUTF8(RecordingStream stream) throws IOException {
    byte id = stream.read();
    if (id == 0) {
      return null;
    } else if (id == 1) {
      return "";
    } else if (id == 3) {
      int size = (int) stream.readVarint();
      byte[] content = size <= COMMON_BUFFER.length ? COMMON_BUFFER : new byte[size];
      stream.read(content, 0, size);
      return new String(content, 0, size, StandardCharsets.UTF_8);
    } else if (id == 4) {
      int size = (int) stream.readVarint();
      char[] chars = new char[size];
      for (int i = 0; i < size; i++) {
        chars[i] = (char) stream.readVarint();
      }
      return new String(chars);
    } else {
      throw new IOException("Unexpected string constant id: " + id);
    }
  }

  @Override
  public String toString() {
    return "Metadata{"
        + "size="
        + size
        + ", startTime="
        + startTime
        + ", duration="
        + duration
        + ", metadataId="
        + metadataId
        + '}';
  }
}
