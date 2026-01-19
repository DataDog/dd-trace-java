package datadog.metrics.api;

import datadog.metrics.statsd.StatsDClient;
import java.util.concurrent.TimeUnit;

public final class MonitoringImpl implements Monitoring {

  private final StatsDClient statsd;
  private final long flushAfterNanos;
  private final boolean enabled;

  public MonitoringImpl(final StatsDClient statsd, long flushInterval, TimeUnit flushUnit) {
    this.statsd = statsd;
    this.flushAfterNanos = flushUnit.toNanos(flushInterval);
    this.enabled = true;
  }

  private MonitoringImpl() {
    this.statsd = StatsDClient.NO_OP;
    this.flushAfterNanos = 0;
    this.enabled = false;
  }

  @Override
  public Recording newTimer(final String name) {
    if (!enabled) {
      return NoOpRecording.NO_OP;
    }
    return new Timer(name, statsd, flushAfterNanos);
  }

  @Override
  public Recording newTimer(final String name, final String... tags) {
    if (!enabled) {
      return NoOpRecording.NO_OP;
    }
    return new Timer(name, tags, statsd, flushAfterNanos);
  }

  @Override
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

  @Override
  public Counter newCounter(final String name) {
    if (!enabled) {
      return NoOpCounter.NO_OP;
    }
    return new StatsDCounter(name, statsd);
  }
}
