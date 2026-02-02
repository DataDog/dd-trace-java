package datadog.trace.test.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

public class AssertionsUtils {
  private AssertionsUtils() {
    // No-op.
  }

  public static void assertMapContainsKeyValues(Map<?, ?> actual, Map<?, ?> expectedSubset) {
    expectedSubset.forEach(
        (k, v) -> {
          Object actualValue = actual.get(k);
          // Compare numbers by their double value for consistency across different formats
          if (v instanceof Number && actualValue instanceof Number) {
            assertEquals(
                ((Number) v).doubleValue(),
                ((Number) actualValue).doubleValue(),
                0.001,
                () -> "Mismatch for key [" + k + "]: expected=" + v + ", actual=" + actual.get(k));
          } else {
            assertEquals(
                v,
                actualValue,
                () -> "Mismatch for key [" + k + "]: expected=" + v + ", actual=" + actual.get(k));
          }
        });
  }
}
