package datadog.trace.instrumentation.jdbc;

import static java.lang.Boolean.TRUE;

/** Shared blocked getConnection() tracking {@link ThreadLocal} for Hikari. */
public class HikariBlockedTracker {
  private static final ThreadLocal<Boolean> tracker = new ThreadLocal<>();

  public static void clearBlocked() {
    tracker.remove();
  }

  public static void setBlocked() {
    tracker.set(TRUE);
  }

  public static boolean wasBlocked() {
    return TRUE.equals(tracker.get());
  }
}
