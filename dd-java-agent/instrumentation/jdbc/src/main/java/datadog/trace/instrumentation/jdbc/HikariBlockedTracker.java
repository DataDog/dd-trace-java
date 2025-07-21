package datadog.trace.instrumentation.jdbc;

/** Shared blocked getConnection() tracking ThreadLocking for Hikari. */
public class HikariBlockedTracker {
  private static final ThreadLocal<Boolean> tracker = ThreadLocal.withInitial(() -> false);

  public static void clearBlocked() {
    tracker.set(false);
  }

  public static void setBlocked() {
    tracker.set(true);
  }

  public static boolean wasBlocked() {
    return tracker.get();
  }
}
