package datadog.trace.core.monitor;

import com.timgroup.statsd.StatsDClient;
import java.util.concurrent.TimeUnit;

public final class Monitoring {

  private final StatsDClient statsd;
  private final long flushAfterNanos;

  public Monitoring(StatsDClient statsd, long flushInterval, TimeUnit flushUnit) {
    this.statsd = statsd;
    this.flushAfterNanos = flushUnit.toNanos(flushInterval);
  }

  public Timer newTimer(final String name) {
    return new Timer(name, statsd, flushAfterNanos);
  }

  public Timer newTimer(final String name, String... tags) {
    return new Timer(name, tags, statsd, flushAfterNanos);
  }

  public ThreadLocal<Timer> newThreadLocalTimer(final String name) {
    return new ThreadLocal<Timer>() {
      @Override
      protected Timer initialValue() {
        return newTimer(name, "thread:" + Thread.currentThread().getName());
      }
    };
  }
}
