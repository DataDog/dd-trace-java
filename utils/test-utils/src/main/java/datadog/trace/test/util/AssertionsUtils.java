package datadog.trace.test.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

public class AssertionsUtils {
  private AssertionsUtils() {
    // No-op.
  }

  public static void assertMapContainsKeyValues(Map<?, ?> actual, Map<?, ?> expectedSubset) {
    expectedSubset.forEach(
        (k, v) ->
            assertEquals(
                v,
                actual.get(k),
                () -> "Mismatch for key [" + k + "]: expected=" + v + ", actual=" + actual.get(k)));
  }
}
