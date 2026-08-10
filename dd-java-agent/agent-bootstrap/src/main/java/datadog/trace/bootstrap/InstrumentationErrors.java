package datadog.trace.bootstrap;

import datadog.trace.api.internal.VisibleForTesting;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class InstrumentationErrors {
  private static final AtomicLong COUNTER = new AtomicLong();

  private static final class Detailed {
    static final List<String> ERRORS = new CopyOnWriteArrayList<>();
  }

  private static volatile boolean detailed;

  /** Record an error occurred without any detail about it. */
  public static void recordError() {
    COUNTER.incrementAndGet();
  }

  /** Record an error occurred, including its stack trace. */
  public static Throwable recordError(Throwable error) {
    COUNTER.incrementAndGet();
    StringWriter detail = new StringWriter();
    error.printStackTrace(new PrintWriter(detail));
    Detailed.ERRORS.add(detail.toString());
    detailed = true;
    return error; // keep throwable at top of the stack
  }

  @VisibleForTesting
  public static void resetErrors() {
    COUNTER.set(0);
    if (detailed) {
      Detailed.ERRORS.clear();
      detailed = false;
    }
  }

  /**
   * @return {@code true} if no errors were recorded; otherwise {@code false}
   */
  public static boolean noErrors() {
    return COUNTER.get() == 0;
  }

  /**
   * @return a human-readable description of the errors recorded so far
   */
  public static String describeErrors() {
    StringBuilder buf = new StringBuilder().append(COUNTER.get()).append(" instrumentation errors");
    if (detailed) {
      for (String error : Detailed.ERRORS) {
        buf.append("\n---\n").append(error);
      }
    }
    return buf.toString();
  }
}
