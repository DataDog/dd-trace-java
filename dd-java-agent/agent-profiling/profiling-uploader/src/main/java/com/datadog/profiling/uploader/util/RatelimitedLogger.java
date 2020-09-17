package com.datadog.profiling.uploader.util;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Logger that logs message once per given delay if debugging is disabled. If debugging is enabled
 * then it logs every time.
 */
// FIXME: move this to a place where it can be reused
public class RatelimitedLogger {

  private final Logger log;
  private final long delay;
  private final Supplier<Long> timeSource;

  private final AtomicLong previousErrorLogNanos = new AtomicLong();

  public RatelimitedLogger(final Logger log, final long delay) {
    this(log, delay, System::nanoTime);
  }

  // Visible for testing
  RatelimitedLogger(final Logger log, final long delay, final Supplier<Long> timeSource) {
    this.log = log;
    this.delay = delay;
    this.timeSource = timeSource;
  }

  public void warn(final String format, final Object... arguments) {
    if (log.isDebugEnabled()) {
      log.warn(format, arguments);
      return;
    }
    if (log.isWarnEnabled()) {
      final long previous = previousErrorLogNanos.get();
      final long now = timeSource.get();
      if (now - previous >= delay) {
        if (previousErrorLogNanos.compareAndSet(previous, now)) {
          log.warn(format + " {} (Will not log errors for 5 minutes)", arguments);
        }
      }
    }
  }
}
