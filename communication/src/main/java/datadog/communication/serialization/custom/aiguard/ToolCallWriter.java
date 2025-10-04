package datadog.communication.serialization.custom.aiguard;

import datadog.communication.serialization.EncodingCache;
import datadog.communication.serialization.ValueWriter;
import datadog.communication.serialization.Writable;
import datadog.trace.api.aiguard.AIGuard;

public class ToolCallWriter implements ValueWriter<AIGuard.ToolCall> {

  @Override
  public void write(
      final AIGuard.ToolCall value, final Writable writable, final EncodingCache encodingCache) {
    writable.startMap(2);
    writable.writeString("id", encodingCache);
    writable.writeString(value.getId(), encodingCache);
    writable.writeString("function", encodingCache);
    writable.writeObject(value.getFunction(), encodingCache);
  }
}
