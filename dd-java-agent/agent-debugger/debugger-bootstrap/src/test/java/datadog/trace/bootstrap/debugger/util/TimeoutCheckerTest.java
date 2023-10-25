package datadog.trace.bootstrap.debugger.util;

import static datadog.trace.bootstrap.debugger.util.TimeoutChecker.DEFAULT_TIME_OUT;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TimeoutCheckerTest {
  @Test
  public void timedOut() {
    long start = System.currentTimeMillis();
    // assume that start & timestamp captured in instance below are very close
    TimeoutChecker timeoutChecker = new TimeoutChecker(DEFAULT_TIME_OUT);
    Assertions.assertFalse(timeoutChecker.isTimedOut(start + 1));
    Assertions.assertTrue(timeoutChecker.isTimedOut(start + DEFAULT_TIME_OUT.toMillis() * 2));
    timeoutChecker = new TimeoutChecker(start, DEFAULT_TIME_OUT);
    Assertions.assertFalse(timeoutChecker.isTimedOut(start + 1));
    Assertions.assertTrue(timeoutChecker.isTimedOut(start + DEFAULT_TIME_OUT.toMillis() * 2));
  }
}
