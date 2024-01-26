package datadog.trace.bootstrap;

import java.util.concurrent.atomic.AtomicLong;

public class InstrumentationErrors {
  private static final AtomicLong COUNTER = new AtomicLong();

  public static long getErrorCount() {
    return COUNTER.get();
  }

  @SuppressWarnings("unused")
  public static void incrementErrorCount() {
    COUNTER.incrementAndGet();
  }

  // Visible for testing
  public static void resetErrorCount() {
    COUNTER.set(0);
  }
}
