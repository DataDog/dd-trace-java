package datadog.trace.api.featureflag.flagevaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlagEvalEventMemoryEstimatorTest {

  @Test
  void estimatesFallbackContextAndEverySupportedValueShape() {
    final Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("string", "value");
    attrs.put("number", 42L);
    attrs.put("empty", null);
    final FlagEvalEvent event =
        new FlagEvalEvent("flag", "on", "allocation", "subject", "error", 1L, true, attrs);

    final long expectedContextBytes =
        FlagEvalEventMemoryEstimator.contextMapRetainedBytes()
            + FlagEvalEventMemoryEstimator.contextEntryRetainedBytes("string", "value")
            + FlagEvalEventMemoryEstimator.contextEntryRetainedBytes("number", 42L)
            + FlagEvalEventMemoryEstimator.contextEntryRetainedBytes("empty", null);

    assertEquals(expectedContextBytes, FlagEvalEventMemoryEstimator.retainedContextBytes(attrs));
    assertEquals(
        128
            + stringBytes("flag")
            + stringBytes("on")
            + stringBytes("allocation")
            + stringBytes("subject")
            + stringBytes("error")
            + expectedContextBytes,
        FlagEvalEventMemoryEstimator.retainedBytes(event));
  }

  @Test
  void usesPrecomputedContextBytesWithoutReadingTheMap() {
    final Map<String, Object> unreadableAttrs =
        new java.util.AbstractMap<String, Object>() {
          @Override
          public java.util.Set<Entry<String, Object>> entrySet() {
            throw new AssertionError("precomputed estimate must avoid a second context walk");
          }
        };
    final FlagEvalEvent event =
        new FlagEvalEvent("flag", null, null, null, null, 1L, true, unreadableAttrs, 512);

    assertEquals(
        128 + stringBytes("flag") + 512, FlagEvalEventMemoryEstimator.retainedBytes(event));
  }

  @Test
  void emptyContextRetainsNoContextBytes() {
    assertEquals(
        0, FlagEvalEventMemoryEstimator.retainedContextBytes(java.util.Collections.emptyMap()));
  }

  private static long stringBytes(final String value) {
    return 40L + 2L * value.length();
  }
}
