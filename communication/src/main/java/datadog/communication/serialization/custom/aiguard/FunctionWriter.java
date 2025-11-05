package datadog.communication.serialization.custom.aiguard;

import datadog.communication.serialization.EncodingCache;
import datadog.communication.serialization.ValueWriter;
import datadog.communication.serialization.Writable;
import datadog.trace.api.aiguard.AIGuard;

public class FunctionWriter implements ValueWriter<AIGuard.ToolCall.Function> {

  @Override
  public void write(
      final AIGuard.ToolCall.Function function,
      final Writable writable,
      final EncodingCache encodingCache) {
    writable.startMap(2);
    writable.writeString("name", encodingCache);
    writable.writeString(function.getName(), encodingCache);
    writable.writeString("arguments", encodingCache);
    writable.writeString(function.getArguments(), encodingCache);
  }
}
