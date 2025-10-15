package datadog.communication.serialization.custom.aiguard;

import datadog.communication.serialization.EncodingCache;
import datadog.communication.serialization.ValueWriter;
import datadog.communication.serialization.Writable;
import datadog.trace.api.aiguard.AIGuard;
import datadog.trace.util.Strings;
import java.util.List;

public class MessageWriter implements ValueWriter<AIGuard.Message> {

  @Override
  public void write(
      final AIGuard.Message value, final Writable writable, final EncodingCache encodingCache) {
    final int[] size = {0};
    final boolean hasRole = isNotBlank(value.getRole(), size);
    final boolean hasContent = isNotBlank(value.getContent(), size);
    final boolean hasToolCallId = isNotBlank(value.getToolCallId(), size);
    final boolean hasToolCalls = isNotEmpty(value.getToolCalls(), size);
    writable.startMap(size[0]);
    writeString(hasRole, "role", value.getRole(), writable, encodingCache);
    writeString(hasContent, "content", value.getContent(), writable, encodingCache);
    writeString(hasToolCallId, "tool_call_id", value.getToolCallId(), writable, encodingCache);
    writeToolCallArray(hasToolCalls, "tool_calls", value.getToolCalls(), writable, encodingCache);
  }

  private static void writeString(
      final boolean present,
      final String key,
      final String value,
      final Writable writable,
      final EncodingCache encodingCache) {
    if (present) {
      writable.writeString(key, encodingCache);
      writable.writeString(value, encodingCache);
    }
  }

  private static void writeToolCallArray(
      final boolean present,
      final String key,
      final List<AIGuard.ToolCall> values,
      final Writable writable,
      final EncodingCache encodingCache) {
    if (present) {
      writable.writeString(key, encodingCache);
      writable.writeObject(values, encodingCache);
    }
  }

  private static boolean isNotBlank(final String value, final int[] nonBlankCount) {
    final boolean hasText = Strings.isNotBlank(value);
    if (hasText) {
      nonBlankCount[0]++;
    }
    return hasText;
  }

  private static boolean isNotEmpty(final List<?> value, final int[] nonEmptyCount) {
    final boolean nonEmpty = value != null && !value.isEmpty();
    if (nonEmpty) {
      nonEmptyCount[0]++;
    }
    return nonEmpty;
  }
}
