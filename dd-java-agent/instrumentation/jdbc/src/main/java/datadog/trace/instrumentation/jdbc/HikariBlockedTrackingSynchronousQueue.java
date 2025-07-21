package datadog.trace.instrumentation.jdbc;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/** Blocked getConnection() tracking for Hikari starting with commit f0b3c520c. */
public class HikariBlockedTrackingSynchronousQueue<T> extends SynchronousQueue<T> {
  public HikariBlockedTrackingSynchronousQueue() {
    // This assumes the initialization of the SynchronousQueue in ConcurrentBag doesn't change
    super(true);
  }

  @Override
  public T poll(long timeout, TimeUnit unit) throws InterruptedException {
    HikariBlockedTracker.setBlocked();
    return super.poll(timeout, unit);
  }
}
