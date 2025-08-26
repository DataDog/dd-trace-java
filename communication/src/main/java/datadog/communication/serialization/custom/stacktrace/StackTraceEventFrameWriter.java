package datadog.communication.serialization.custom.stacktrace;

import datadog.communication.serialization.EncodingCache;
import datadog.communication.serialization.ValueWriter;
import datadog.communication.serialization.Writable;
import datadog.config.util.Strings;
import datadog.trace.util.stacktrace.StackTraceFrame;

public class StackTraceEventFrameWriter implements ValueWriter<StackTraceFrame> {

  @Override
  public void write(StackTraceFrame value, Writable writable, EncodingCache encodingCache) {
    int mapSize = 1; // id always present
    boolean hasText = Strings.isNotBlank(value.getText());
    boolean hasFile = Strings.isNotBlank(value.getFile());
    boolean hasLine = value.getLine() != null;
    boolean hasClass = Strings.isNotBlank(value.getClass_name());
    boolean hasFunction = Strings.isNotBlank(value.getFunction());
    if (hasText) {
      mapSize++;
    }
    if (hasFile) {
      mapSize++;
    }
    if (hasLine) {
      mapSize++;
    }
    if (hasClass) {
      mapSize++;
    }
    if (hasFunction) {
      mapSize++;
    }
    writable.startMap(mapSize);
    writable.writeString("id", encodingCache);
    writable.writeInt(value.getId());
    if (hasText) {
      writable.writeString("text", encodingCache);
      writable.writeString(value.getText(), encodingCache);
    }
    if (hasFile) {
      writable.writeString("file", encodingCache);
      writable.writeString(value.getFile(), encodingCache);
    }
    if (hasLine) {
      writable.writeString("line", encodingCache);
      writable.writeInt(value.getLine());
    }
    if (hasClass) {
      writable.writeString("class_name", encodingCache);
      writable.writeString(value.getClass_name(), encodingCache);
    }
    if (hasFunction) {
      writable.writeString("function", encodingCache);
      writable.writeString(value.getFunction(), encodingCache);
    }
  }
}
