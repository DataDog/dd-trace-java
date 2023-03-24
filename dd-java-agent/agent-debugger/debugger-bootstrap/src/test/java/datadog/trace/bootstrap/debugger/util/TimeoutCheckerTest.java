package datadog.trace.bootstrap.debugger.util;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TimeoutCheckerTest {
  private static final Duration TIME_OUT = Duration.of(100, ChronoUnit.MILLIS);

  @Test
  public void timedOut() {
    long start = System.currentTimeMillis();
    // assume that start & timestamp captured in instance below are very close
    TimeoutChecker timeoutChecker = new TimeoutChecker(TIME_OUT);
    Assertions.assertFalse(timeoutChecker.isTimedOut(start + 1));
    Assertions.assertTrue(timeoutChecker.isTimedOut(start + TIME_OUT.toMillis() * 2));
    timeoutChecker = new TimeoutChecker(start, TIME_OUT);
    Assertions.assertFalse(timeoutChecker.isTimedOut(start + 1));
    Assertions.assertTrue(timeoutChecker.isTimedOut(start + TIME_OUT.toMillis() * 2));
  }
}
