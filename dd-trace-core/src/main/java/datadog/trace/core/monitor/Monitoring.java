package datadog.trace.core.monitor;

import com.timgroup.statsd.StatsDClient;
import java.util.concurrent.TimeUnit;

public final class Monitoring {

  public static final Monitoring DISABLED = new Monitoring();

  private final StatsDClient statsd;
  private final long flushAfterNanos;
  private final boolean enabled;

  public Monitoring(StatsDClient statsd, long flushInterval, TimeUnit flushUnit) {
    this.statsd = statsd;
    this.flushAfterNanos = flushUnit.toNanos(flushInterval);
    this.enabled = true;
  }

  private Monitoring() {
    this.statsd = null;
    this.flushAfterNanos = 0;
    this.enabled = false;
  }

  public Recording newTimer(final String name) {
    if (!enabled) {
      return NoOpRecording.NO_OP;
    }
    return new Timer(name, statsd, flushAfterNanos);
  }

  public Recording newTimer(final String name, String... tags) {
    if (!enabled) {
      return NoOpRecording.NO_OP;
    }
    return new Timer(name, tags, statsd, flushAfterNanos);
  }

  public Recording newThreadLocalTimer(final String name) {
    if (!enabled) {
      return NoOpRecording.NO_OP;
    }
    return new ThreadLocalRecording(
        new ThreadLocal<Recording>() {
          @Override
          protected Recording initialValue() {
            return newTimer(name, "thread:" + Thread.currentThread().getName());
          }
        });
  }
}
