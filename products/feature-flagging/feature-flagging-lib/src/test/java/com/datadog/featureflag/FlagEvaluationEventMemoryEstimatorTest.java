package com.datadog.featureflag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEventMemoryEstimator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlagEvaluationEventMemoryEstimatorTest {

  @Test
  void estimatesScalarOnlyEvent() {
    final FlagEvalEvent event =
        new FlagEvalEvent("flag", "on", "allocation", "subject", "error", 1L, false, null);

    assertEquals(
        128
            + stringBytes("flag")
            + stringBytes("on")
            + stringBytes("allocation")
            + stringBytes("subject")
            + stringBytes("error"),
        FlagEvalEventMemoryEstimator.retainedBytes(event));
  }

  @Test
  void estimatesEverySupportedContextValueShape() {
    final Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("string", "value");
    attrs.put("number", 42L);
    attrs.put("empty", null);
    final FlagEvalEvent event = new FlagEvalEvent("flag", null, null, null, null, 1L, true, attrs);

    final long expectedContextBytes =
        64
            + 48
            + stringBytes("string")
            + stringBytes("value")
            + 48
            + stringBytes("number")
            + 128
            + 48
            + stringBytes("empty");
    assertEquals(
        128 + stringBytes("flag") + expectedContextBytes,
        FlagEvalEventMemoryEstimator.retainedBytes(event));
  }

  @Test
  void selectedBudgetKeepsTypicalCapacityAndBoundsWideContexts() {
    final long budget = FlagEvaluationWriterImpl.DEFAULT_QUEUE_RETAINED_BYTE_BUDGET;
    final long typicalBytes =
        FlagEvalEventMemoryEstimator.retainedBytes(eventWithContext(10, 16, 32));
    final long nestedBytes =
        FlagEvalEventMemoryEstimator.retainedBytes(eventWithContext(100, 32, 32));
    final long maximumBytes =
        FlagEvalEventMemoryEstimator.retainedBytes(eventWithContext(256, 256, 256));

    assertEquals(2_880, typicalBytes);
    assertEquals(26_240, nestedBytes);
    assertEquals(295_552, maximumBytes);
    assertEquals(4_096, Math.min(4_096, budget / typicalBytes));
    assertEquals(639, budget / nestedBytes);
    assertEquals(56, budget / maximumBytes);
  }

  private static FlagEvalEvent eventWithContext(
      final int fields, final int keyLength, final int valueLength) {
    final Map<String, Object> attrs = new LinkedHashMap<>();
    for (int field = 0; field < fields; field++) {
      attrs.put(
          fixed("key-" + field + "-", keyLength, 'k'),
          fixed("value-" + field + "-", valueLength, 'v'));
    }
    return new FlagEvalEvent(
        fixed("flag-", 32, 'f'),
        fixed("variant-", 16, 'v'),
        fixed("allocation-", 32, 'a'),
        fixed("target-", 64, 't'),
        null,
        1L,
        true,
        attrs);
  }

  private static String fixed(final String prefix, final int length, final char fillCharacter) {
    final StringBuilder value = new StringBuilder(length).append(prefix);
    while (value.length() < length) {
      value.append(fillCharacter);
    }
    return value.substring(0, length);
  }

  private static long stringBytes(final String value) {
    return 40L + 2L * value.length();
  }
}
