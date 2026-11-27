package datadog.trace.bootstrap.debugger.util;

import java.time.Duration;

/**
 * Wall-clock {@link TimeoutChecker}. When created with a {@link PauseDetector}, process hiccups
 * (mainly JVM stop-the-world pauses) detected since the checker was created are not counted against
 * the timeout.
 */
public class WallTimeoutChecker implements TimeoutChecker {

  private final long start;
  private final Duration timeOut;
  private final PauseDetector pauseDetector;
  private final long startPauseMs;

  public WallTimeoutChecker(Duration timeOut) {
    this(System.currentTimeMillis(), timeOut, null);
  }

  public WallTimeoutChecker(long start, Duration timeOut) {
    this(start, timeOut, null);
  }

  /**
   * @param pauseDetector detector whose pauses are compensated, or {@code null} for no compensation
   */
  public WallTimeoutChecker(Duration timeOut, PauseDetector pauseDetector) {
    this(System.currentTimeMillis(), timeOut, pauseDetector);
  }

  WallTimeoutChecker(long start, Duration timeOut, PauseDetector pauseDetector) {
    this.start = start;
    this.timeOut = timeOut;
    this.pauseDetector = pauseDetector;
    this.startPauseMs = pauseDetector != null ? pauseDetector.getTotalPauseMs() : 0;
  }

  public boolean isTimedOut(long currentTimeMillis) {
    long elapsedMs = currentTimeMillis - start;
    if (pauseDetector != null) {
      elapsedMs -= pauseDetector.getTotalPauseMs() - startPauseMs;
    }
    return elapsedMs > timeOut.toMillis();
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

  /** Returns whether detected pauses are compensated. */
  public boolean isCompensatingPauses() {
    return pauseDetector != null;
  }
}
