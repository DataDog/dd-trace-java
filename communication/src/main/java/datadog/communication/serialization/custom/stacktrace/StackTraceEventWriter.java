package datadog.communication.serialization.custom.stacktrace;

import datadog.communication.serialization.EncodingCache;
import datadog.communication.serialization.ValueWriter;
import datadog.communication.serialization.Writable;
import datadog.config.util.Strings;
import datadog.trace.util.stacktrace.StackTraceEvent;

public class StackTraceEventWriter implements ValueWriter<StackTraceEvent> {

  @Override
  public void write(StackTraceEvent value, Writable writable, EncodingCache encodingCache) {
    int mapSize = 1; // frames always present
    boolean hasId = Strings.isNotBlank(value.getId());
    boolean hasLanguage = Strings.isNotBlank(value.getLanguage());
    boolean hasMessage = Strings.isNotBlank(value.getMessage());
    if (hasId) {
      mapSize++;
    }
    if (hasLanguage) {
      mapSize++;
    }
    if (hasMessage) {
      mapSize++;
    }
    writable.startMap(mapSize);
    if (hasId) {
      writable.writeString("id", encodingCache);
      writable.writeString(value.getId(), encodingCache);
    }
    if (hasLanguage) {
      writable.writeString("language", encodingCache);
      writable.writeString(value.getLanguage(), encodingCache);
    }
    if (hasMessage) {
      writable.writeString("message", encodingCache);
      writable.writeString(value.getMessage(), encodingCache);
    }
    writable.writeString("frames", encodingCache);
    writable.writeObject(value.getFrames(), encodingCache);
  }
}
