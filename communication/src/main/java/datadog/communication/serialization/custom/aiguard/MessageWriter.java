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
    final boolean hasRole = present(Strings.isNotBlank(value.getRole()), size);
    final boolean hasToolCallId = present(Strings.isNotBlank(value.getToolCallId()), size);
    final boolean hasToolCalls = present(isNotEmpty(value.getToolCalls()), size);

    final boolean hasContentParts = present(isNotEmpty(value.getContentParts()), size);
    // An empty content string is still written: "" is what the redaction remove strategy leaves
    // behind, and dropping it would be indistinguishable from a message that never had content.
    final boolean hasContentString = present(!hasContentParts && value.getContent() != null, size);

    writable.startMap(size[0]);
    writeString(hasRole, "role", value.getRole(), writable, encodingCache);

    if (hasContentParts) {
      writeContentParts("content", value.getContentParts(), writable, encodingCache);
    } else {
      writeString(hasContentString, "content", value.getContent(), writable, encodingCache);
    }

    writeString(hasToolCallId, "tool_call_id", value.getToolCallId(), writable, encodingCache);
    writeToolCallArray(hasToolCalls, "tool_calls", value.getToolCalls(), writable, encodingCache);
  }

  private static void writeContentParts(
      final String key,
      final List<AIGuard.ContentPart> contentParts,
      final Writable writable,
      final EncodingCache encodingCache) {
    writable.writeString(key, encodingCache);
    writable.startArray(contentParts.size());

    for (final AIGuard.ContentPart part : contentParts) {
      writable.startMap(2);

      writable.writeString("type", encodingCache);
      writable.writeString(part.getType().toString(), encodingCache);

      if (part.getType() == AIGuard.ContentPart.Type.TEXT) {
        writable.writeString("text", encodingCache);
        writable.writeString(part.getText(), encodingCache);
      } else if (part.getType() == AIGuard.ContentPart.Type.IMAGE_URL) {
        writable.writeString("image_url", encodingCache);
        writable.startMap(1);
        writable.writeString("url", encodingCache);
        writable.writeString(part.getImageUrl().getUrl(), encodingCache);
      }
    }
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

  /** Counts a field towards the map size when it is present, and reports whether it is. */
  private static boolean present(final boolean present, final int[] fieldCount) {
    if (present) {
      fieldCount[0]++;
    }
    return present;
  }

  private static boolean isNotEmpty(final List<?> value) {
    return value != null && !value.isEmpty();
  }
}
