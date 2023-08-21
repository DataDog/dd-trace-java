package datadog.trace.bootstrap.debugger.util;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

public class TimeoutChecker {
  public static final Duration DEFAULT_TIME_OUT = Duration.of(100, ChronoUnit.MILLIS);

  private final long start;
  private final Duration timeOut;

  public TimeoutChecker(Duration timeOut) {
    this.start = System.currentTimeMillis();
    this.timeOut = timeOut;
  }

  public TimeoutChecker(long start, Duration timeOut) {
    this.start = start;
    this.timeOut = timeOut;
  }

  public boolean isTimedOut(long currentTimeMillis) {
    return (currentTimeMillis - start) > timeOut.toMillis();
  }

  public long getStart() {
    return start;
  }

  public Duration getTimeOut() {
    return timeOut;
  }
}
