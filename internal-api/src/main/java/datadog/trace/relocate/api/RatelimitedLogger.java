package datadog.trace.relocate.api;

import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.Marker;

/**
 * Logger that logs message once per given delay if debugging is disabled. If debugging is enabled
 * then it logs every time.
 */
public class RatelimitedLogger {

  private final Logger log;
  private final long delayNanos;
  private final String noLogMessage;
  private final TimeSource timeSource;

  private final AtomicLong nextLogNanos;

  public RatelimitedLogger(final Logger log, final int delay, final TimeUnit timeUnit) {
    this(log, delay, timeUnit, SystemTimeSource.INSTANCE);
  }

  // Visible for testing
  RatelimitedLogger(
      final Logger log, final int delay, final TimeUnit timeUnit, final TimeSource timeSource) {
    this.log = log;
    this.delayNanos = timeUnit.toNanos(delay);
    this.noLogMessage = createNoLogMessage(" (Will not log warnings for ", ")", delay, timeUnit);
    this.timeSource = timeSource;
    nextLogNanos = new AtomicLong(timeSource.getNanoTicks());
  }

  /**
   * @return true if actually logged the message, false otherwise
   */
  public boolean warn(final String format, final Object... arguments) {
    return warn(null, format, arguments);
  }

  public boolean warn(Marker marker, final String format, final Object... arguments) {
    if (log.isDebugEnabled()) {
      log.warn(marker, format, arguments);
      return true;
    }
    if (log.isWarnEnabled()) {
      final long next = nextLogNanos.get();
      final long now = timeSource.getNanoTicks();
      if (now - next >= 0 && nextLogNanos.compareAndSet(next, now + delayNanos)) {
        log.warn(marker, format + noLogMessage, arguments);
        return true;
      }
    }
    return false;
  }

  private static String createNoLogMessage(
      String prefix, String postfix, int delay, TimeUnit timeUnit) {
    StringBuilder noLogStringBuilder = new StringBuilder(prefix);
    noLogStringBuilder.append(delay);
    noLogStringBuilder.append(' ');
    String unit = timeUnit.name().toLowerCase(Locale.ROOT);
    unit =
        delay == 1
            ? unit.substring(0, unit.length() - 1)
            : unit; // should we drop the plural s or not?
    noLogStringBuilder.append(unit);
    noLogStringBuilder.append(postfix);
    return noLogStringBuilder.toString();
  }
}
