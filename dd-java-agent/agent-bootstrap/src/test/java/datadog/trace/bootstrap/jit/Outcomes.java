package datadog.trace.bootstrap.jit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/** Compares the outcome (result, or exception type and message) of an original and a helper. */
final class Outcomes {
  private Outcomes() {}

  static void assertSameOutcome(Callable<?> expected, Callable<?> actual) {
    Object expectedResult = outcome(expected);
    Object actualResult = outcome(actual);
    if (expectedResult instanceof String[] && actualResult instanceof String[]) {
      assertArrayEquals((String[]) expectedResult, (String[]) actualResult);
    } else {
      assertEquals(expectedResult, actualResult);
    }
  }

  /** The result, or a description of the exception thrown. */
  static Object outcome(Callable<?> call) {
    try {
      return call.call();
    } catch (Throwable e) {
      return describe(e);
    }
  }

  static List<String> describe(Throwable e) {
    List<String> d = new ArrayList<>(Arrays.asList(e.getClass().getName()));
    if (!(e instanceof NullPointerException)) {
      d.add(String.valueOf(e.getMessage())); // helpful NPE messages name the caller's variables
    }
    return d;
  }
}
