package datadog.trace.api;

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * Logger that logs message once per given delay if debugging is disabled. If debugging is enabled
 * then it logs every time.
 */
// FIXME: move this to a place where it can be reused
public class RatelimitedLogger {

  public interface TimeSourceSupplier {
    TimeSourceSupplier DEFAULT =
        new TimeSourceSupplier() {
          @Override
          public long get() {
            return System.nanoTime();
          }
        };

    long get();
  }

  private final Logger log;
  private final long delay;
  private final TimeSourceSupplier timeSource;

  private final AtomicLong previousErrorLogNanos = new AtomicLong();

  public RatelimitedLogger(final Logger log, final long delay) {
    this(log, delay, TimeSourceSupplier.DEFAULT);
  }

  // Visible for testing
  RatelimitedLogger(final Logger log, final long delay, final TimeSourceSupplier timeSource) {
    this.log = log;
    this.delay = delay;
    this.timeSource = timeSource;
  }

  /** @return true if actually logged the message, false otherwise */
  public boolean warn(final String format, final Object... arguments) {
    if (log.isDebugEnabled()) {
      log.warn(format, arguments);
      return true;
    }
    if (log.isWarnEnabled()) {
      final long previous = previousErrorLogNanos.get();
      final long now = timeSource.get();
      if (now - previous >= delay) {
        if (previousErrorLogNanos.compareAndSet(previous, now)) {
          log.warn(format + " (Will not log errors for 5 minutes)", arguments);
          return true;
        }
      }
    }
    return false;
  }
}
