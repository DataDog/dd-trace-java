package datadog.trace.core.monitor;

import static datadog.trace.api.Platform.isJavaVersionAtLeast;

import datadog.trace.api.StatsDClient;
import java.util.concurrent.TimeUnit;

public final class Monitoring {

  public static final Monitoring DISABLED = new Monitoring();

  private final StatsDClient statsd;
  private final long flushAfterNanos;
  private final boolean enabled;

  public Monitoring(final StatsDClient statsd, long flushInterval, TimeUnit flushUnit) {
    this.statsd = statsd;
    this.flushAfterNanos = flushUnit.toNanos(flushInterval);
    this.enabled = true;
  }

  private Monitoring() {
    this.statsd = StatsDClient.NO_OP;
    this.flushAfterNanos = 0;
    this.enabled = false;
  }

  public Recording newTimer(final String name) {
    if (!enabled || !isJavaVersionAtLeast(8)) {
      return NoOpRecording.NO_OP;
    }
    return new Timer(name, statsd, flushAfterNanos);
  }

  public Recording newTimer(final String name, final String... tags) {
    if (!enabled || !isJavaVersionAtLeast(8)) {
      return NoOpRecording.NO_OP;
    }
    return new Timer(name, tags, statsd, flushAfterNanos);
  }

  public Recording newThreadLocalTimer(final String name) {
    if (!enabled || !isJavaVersionAtLeast(8)) {
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

  public Counter newCounter(final String name) {
    if (!enabled) {
      return NoOpCounter.NO_OP;
    }
    return new StatsDCounter(name, statsd);
  }
}
