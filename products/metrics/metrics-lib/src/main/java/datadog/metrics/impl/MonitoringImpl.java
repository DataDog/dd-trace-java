package datadog.metrics.impl;

import datadog.metrics.api.Counter;
import datadog.metrics.api.Monitoring;
import datadog.metrics.api.Recording;
import datadog.metrics.api.statsd.StatsDClient;
import java.util.concurrent.TimeUnit;

public final class MonitoringImpl implements Monitoring {
  private final StatsDClient statsd;
  private final long flushAfterNanos;

  public MonitoringImpl(final StatsDClient statsd, long flushInterval, TimeUnit flushUnit) {
    this.statsd = statsd;
    this.flushAfterNanos = flushUnit.toNanos(flushInterval);
  }

  @Override
  public Recording newTimer(final String name) {
    return new Timer(name, statsd, flushAfterNanos);
  }

  @Override
  public Recording newTimer(final String name, final String... tags) {
    return new Timer(name, tags, statsd, flushAfterNanos);
  }

  @Override
  public Recording newThreadLocalTimer(final String name) {
    return new ThreadLocalRecording(
        ThreadLocal.withInitial(
            () -> newTimer(name, "thread:" + Thread.currentThread().getName())));
  }

  @Override
  public Counter newCounter(final String name) {
    return new StatsDCounter(name, statsd);
  }
}
