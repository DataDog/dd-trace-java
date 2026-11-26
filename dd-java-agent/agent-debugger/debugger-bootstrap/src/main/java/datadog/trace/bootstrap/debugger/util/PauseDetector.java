package datadog.trace.bootstrap.debugger.util;

import static datadog.trace.util.AgentThreadFactory.AgentThread.DEBUGGER_PAUSE_DETECTOR;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * <p>The shared detector is started explicitly with {@link #start()} from an agent thread, never
 * lazily from an application thread: a thread created by an application thread would inherit its
 * {@link InheritableThreadLocal} values and could keep them reachable for the JVM lifetime.
 */
public final class PauseDetector {
  private static final Logger LOGGER = LoggerFactory.getLogger(PauseDetector.class);

  static final long SLEEP_MS = 1;
  static final long SKEW_THRESHOLD_MS = 2;

  /** Sleeps the detector thread, replaceable in tests to simulate pauses. */
  interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }

  private static final AtomicBoolean STARTED = new AtomicBoolean();
  private static volatile PauseDetector instance;

  private final Sleeper sleeper;
  private final AtomicLong totalPauseMs = new AtomicLong();

  PauseDetector() {
    this(Thread::sleep);
  }

  PauseDetector(Sleeper sleeper) {
    this.sleeper = sleeper;
  }

  /**
   * Starts the shared detector, only the first call has an effect. Must be called from an agent
   * thread. A failure to start the detector thread is logged and leaves {@link #get()} returning
   * {@code null}.
   */
  public static void start() {
    if (!STARTED.compareAndSet(false, true)) {
      return;
    }
    try {
      PauseDetector detector = new PauseDetector();
      newAgentThread(DEBUGGER_PAUSE_DETECTOR, detector::run).start();
      instance = detector;
    } catch (Throwable t) {
      LOGGER.warn("Failed to start pause detector, wall-clock timeouts are not compensated", t);
    }
  }

  /** Returns the shared detector, or {@code null} if it has not been started. */
  public static PauseDetector get() {
    return instance;
  }

  /** Returns the accumulated pause time in milliseconds since the detector started. */
  public long getTotalPauseMs() {
    return totalPauseMs.get();
  }

  /** Records one measured sleep, accumulating its skew when it is a hiccup. */
  void onSleep(long elapsedMs) {
    long skew = elapsedMs - SLEEP_MS;
    if (skew >= SKEW_THRESHOLD_MS) {
      totalPauseMs.addAndGet(skew);
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
}
