package datadog.trace.api.featureflag.flagevaluation;

import java.util.Map;

/** Conservatively estimates memory retained while an event waits in the evaluation queue. */
public final class FlagEvalEventMemoryEstimator {

  public static final long UNKNOWN_RETAINED_BYTES = -1;

  // These constants include aligned object headers, references, and amortized queue or map storage.
  // They use the same string and context-entry model as the aggregation byte budget.
  private static final long EVENT_AND_QUEUE_ENTRY_BYTES = 128;
  private static final long CONTEXT_MAP_BYTES = 64;
  private static final long CONTEXT_ENTRY_BYTES = 48;
  private static final long STRING_BYTES = 40;
  private static final long OTHER_VALUE_BYTES = 128;
  private static final long MAX_BYTES_PER_CHARACTER = 2;

  private FlagEvalEventMemoryEstimator() {}

  public static long retainedBytes(final FlagEvalEvent event) {
    long bytes = EVENT_AND_QUEUE_ENTRY_BYTES;
    bytes = add(bytes, stringBytes(event.flagKey));
    bytes = add(bytes, stringBytes(event.variant));
    bytes = add(bytes, stringBytes(event.allocationKey));
    bytes = add(bytes, stringBytes(event.targetingKey));
    bytes = add(bytes, stringBytes(event.errorMessage));
    final long contextBytes =
        event.estimatedContextRetainedBytes < 0
            ? retainedContextBytes(event.attrs)
            : event.estimatedContextRetainedBytes;
    return add(bytes, contextBytes);
  }

  public static long retainedContextBytes(final Map<String, Object> attrs) {
    if (attrs.isEmpty()) {
      return 0;
    }
    long bytes = contextMapRetainedBytes();
    for (final Map.Entry<String, Object> entry : attrs.entrySet()) {
      bytes = add(bytes, contextEntryRetainedBytes(entry.getKey(), entry.getValue()));
    }
    return bytes;
  }

  public static long contextMapRetainedBytes() {
    return CONTEXT_MAP_BYTES;
  }

  public static long contextEntryRetainedBytes(final String key, final Object value) {
    return add(CONTEXT_ENTRY_BYTES, add(stringBytes(key), contextValueBytes(value)));
  }

  private static long contextValueBytes(final Object value) {
    if (value instanceof String) {
      return stringBytes((String) value);
    }
    return value == null ? 0 : OTHER_VALUE_BYTES;
  }

  private static long stringBytes(final String value) {
    return value == null ? 0 : add(STRING_BYTES, characterBytes(value.length()));
  }

  private static long characterBytes(final int length) {
    return MAX_BYTES_PER_CHARACTER * length;
  }

  private static long add(final long left, final long right) {
    // Both inputs describe live Java objects. Their sum cannot approach the long range in one JVM.
    return left + right;
  }
}
