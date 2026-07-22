package datadog.trace.bootstrap.debugger.util;

import java.time.Duration;

public class WallTimeoutChecker implements TimeoutChecker {

  private final long start;
  private final Duration timeOut;

  public WallTimeoutChecker(Duration timeOut) {
    this.start = System.currentTimeMillis();
    this.timeOut = timeOut;
  }

  public WallTimeoutChecker(long start, Duration timeOut) {
    this.start = start;
    this.timeOut = timeOut;
  }

  public boolean isTimedOut(long currentTimeMillis) {
    return (currentTimeMillis - start) > timeOut.toMillis();
  }

  @Override
  public boolean isTimedOut() {
    return isTimedOut(System.currentTimeMillis());
  }

  public long getStart() {
    return start;
  }

  @Override
  public Duration getTimeOut() {
    return timeOut;
  }
}
