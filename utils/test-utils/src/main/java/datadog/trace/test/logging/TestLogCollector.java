package datadog.trace.test.logging;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicReference;

public final class TestLogCollector {
  static final TestLogCollector INSTANCE = new TestLogCollector();

  private final AtomicReference<LinkedBlockingDeque<CapturedLog>> logs =
      new AtomicReference<>(null);

  private TestLogCollector() {}

  /**
   * Enable the test log collector. This should be called before any test that needs the logs.
   * {@link #disable()} must always be called at cleanup.
   */
  public static void enable() {
    INSTANCE._enable();
  }

  /** Must be called at least once after {@link #enable()} to cleanup the test log collector. */
  public static void disable() {
    INSTANCE._disable();
  }

  /**
   * Get all captured logs and clear the internal buffer. {@link #enable()} must have been called
   * before.
   */
  public static List<CapturedLog> drainCapturedLogs() {
    return INSTANCE._drainCapturedLogs();
  }

  private void _enable() {
    final LinkedBlockingDeque<CapturedLog> logs = new LinkedBlockingDeque<>();
    if (!this.logs.compareAndSet(null, logs)) {
      throw new IllegalStateException("TestLogCollector was enabled without prior cleanup");
    }
  }

  private List<CapturedLog> _drainCapturedLogs() {
    final List<CapturedLog> result = new ArrayList<>();
    final LinkedBlockingDeque<CapturedLog> logs = this.logs.get();
    if (logs == null) {
      throw new IllegalStateException("TestLogCollector was not enabled before draining logs");
    }
    logs.drainTo(result);
    return result;
  }

  private void _disable() {
    final LinkedBlockingDeque<CapturedLog> logs = this.logs.getAndSet(null);
    if (logs == null) {
      return;
    }
    logs.clear();
  }

  void addLog(final CapturedLog log) {
    final LinkedBlockingDeque<CapturedLog> logs = this.logs.get();
    if (logs != null) {
      logs.add(log);
    }
  }
}
