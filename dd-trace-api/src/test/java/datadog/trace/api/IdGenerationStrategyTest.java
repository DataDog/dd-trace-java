package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class IdGenerationStrategyTest {

  @ParameterizedTest(name = "generate id with {1} and {0} bits")
  @CsvSource(
      value = {
        "false|RANDOM",
        "false|SEQUENTIAL",
        "false|SECURE_RANDOM",
        "true|RANDOM",
        "true|SEQUENTIAL",
        "true|SECURE_RANDOM"
      },
      delimiter = '|')
  void generateIdWithStrategyAndBitSize(
      boolean traceId128BitGenerationEnabled, String strategyName) {
    IdGenerationStrategy strategy =
        IdGenerationStrategy.fromName(strategyName, traceId128BitGenerationEnabled);
    Set<DDTraceId> checked = new HashSet<DDTraceId>();

    for (int index = 0; index <= 32768; index++) {
      DDTraceId traceId = strategy.generateTraceId();
      assertNotNull(traceId);
      assertFalse(traceId.equals(null));
      assertFalse(traceId.equals("foo"));
      assertNotEquals(DDTraceId.ZERO, traceId);
      assertTrue(traceId.equals(traceId));

      int expectedHash =
          (int)
              (traceId.toHighOrderLong()
                  ^ (traceId.toHighOrderLong() >>> 32)
                  ^ traceId.toLong()
                  ^ (traceId.toLong() >>> 32));
      assertEquals(expectedHash, traceId.hashCode());

      assertFalse(checked.contains(traceId));
      checked.add(traceId);
    }
  }

  @ParameterizedTest(name = "return null for non existing strategy {0}")
  @ValueSource(strings = {"SOME", "UNKNOWN", "STRATEGIES"})
  void returnNullForNonExistingStrategy(String strategyName) {
    assertNull(IdGenerationStrategy.fromName(strategyName));
  }

  @org.junit.jupiter.api.Test
  void exceptionCreatedOnSecureRandomStrategy() {
    ExceptionInInitializerError error =
        assertThrows(
            ExceptionInInitializerError.class,
            () ->
                new IdGenerationStrategy.SRandom(
                    false,
                    () -> {
                      throw new IllegalArgumentException("SecureRandom init exception");
                    }));

    assertNotNull(error.getCause());
    assertEquals("SecureRandom init exception", error.getCause().getMessage());
  }

  @org.junit.jupiter.api.Test
  void secureRandomIdsWillAlwaysBeNonZero() {
    ScriptedSecureRandom random = new ScriptedSecureRandom(new long[] {0L, 47L, 0L, 11L});
    CallCounter providerCallCounter = new CallCounter();

    IdGenerationStrategy strategy =
        new IdGenerationStrategy.SRandom(
            false,
            () -> {
              providerCallCounter.count++;
              return random;
            });

    long traceId = strategy.generateTraceId().toLong();
    long spanId = strategy.generateSpanId();

    assertEquals(1, providerCallCounter.count);
    assertEquals(47L, traceId);
    assertEquals(11L, spanId);
    assertEquals(4, random.calls);
  }

  private static class CallCounter {
    private int count;
  }

  private static class ScriptedSecureRandom extends SecureRandom {
    private final long[] values;
    private int index;
    private int calls;

    private ScriptedSecureRandom(long[] values) {
      this.values = values;
    }

    @Override
    public long nextLong() {
      calls++;
      if (index >= values.length) {
        return 0L;
      }
      long value = values[index];
      index++;
      return value;
    }
  }
}
