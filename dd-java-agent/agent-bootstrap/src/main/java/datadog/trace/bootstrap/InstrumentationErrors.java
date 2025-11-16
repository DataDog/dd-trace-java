package datadog.trace.bootstrap;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class InstrumentationErrors {
  private static final List<String> ERRORS = new CopyOnWriteArrayList<>();
  private static volatile boolean recordErrors = false;

  /**
   * Record an error from {@link datadog.trace.agent.tooling.bytebuddy.ExceptionHandlers} for test
   * visibility.
   */
  @SuppressWarnings("unused")
  public static void recordError(final Throwable throwable) {
    if (recordErrors) {
      StringWriter stackTrace = new StringWriter();
      throwable.printStackTrace(new PrintWriter(stackTrace));
      ERRORS.add(stackTrace.toString());
    }
  }

  // Visible for testing
  public static void enableRecordingAndReset() {
    recordErrors = true;
    ERRORS.clear();
  }

  // Visible for testing
  public static List<String> getErrors() {
    return Collections.unmodifiableList(ERRORS);
  }
}
