package datadog.trace.bootstrap.debugger.util;

import static datadog.trace.util.AgentThreadFactory.AgentThread.DEBUGGER_PAUSE_DETECTOR;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

/**
 * Detects hiccups of the process, mainly JVM stop-the-world events like GCs, and accumulates their
 * duration.
 *
 * <p>A daemon thread repeatedly sleeps {@link #SLEEP_MS} and measures the actual sleep time with
 * {@link System#currentTimeMillis()}. When the measured time exceeds the expected one by at least
 * {@link #SKEW_THRESHOLD_MS}, the whole skew is added to {@link #getTotalPauseMs()}.
 *
 * <p>A pause is only accounted once the detector thread wakes up after it, so a pause still in
 * progress is not visible yet.
 */
public final class PauseDetector {
  static final long SLEEP_MS = 1;
  static final long SKEW_THRESHOLD_MS = 2;

  /** Sleeps the detector thread, replaceable in tests to simulate pauses. */
  interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }

  private final Sleeper sleeper;
  // single writer: the detector thread
  private volatile long totalPauseMs;

  PauseDetector() {
    this(Thread::sleep);
  }

  PauseDetector(Sleeper sleeper) {
    this.sleeper = sleeper;
  }

  /** Returns the shared detector, starting its thread on first use. */
  public static PauseDetector get() {
    return Holder.INSTANCE;
  }

  /** Returns the accumulated pause time in milliseconds since the detector started. */
  public long getTotalPauseMs() {
    return totalPauseMs;
  }

  /** Records one measured sleep, accumulating its skew when it is a hiccup. */
  void onSleep(long elapsedMs) {
    long skew = elapsedMs - SLEEP_MS;
    if (skew >= SKEW_THRESHOLD_MS) {
      totalPauseMs += skew;
    }
  }

  /** Detector loop, exits when the running thread is interrupted. */
  void run() {
    while (!Thread.currentThread().isInterrupted()) {
      long before = System.currentTimeMillis();
      try {
        sleeper.sleep(SLEEP_MS);
      } catch (InterruptedException e) {
        return;
      }
      onSleep(System.currentTimeMillis() - before);
    }
  }

  private static final class Holder {
    static final PauseDetector INSTANCE = start();

    private static PauseDetector start() {
      PauseDetector detector = new PauseDetector();
      newAgentThread(DEBUGGER_PAUSE_DETECTOR, detector::run).start();
      return detector;
    }
  }
}
