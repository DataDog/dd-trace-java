package datadog.trace.core.monitor;

import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.StatsDClient;
import datadog.trace.api.rum.RumTelemetryCollector;
import datadog.trace.util.AgentTaskScheduler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jctools.counters.CountersFactory;
import org.jctools.counters.FixedSizeStripedLongCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Default implementation of RumInjectorHealthMetrics that reports metrics via StatsDClient
// This class implements the RumTelemetryCollector interface, which is used to collect telemetry
// from the RumInjector in the internal-api module
public class DefaultRumInjectorHealthMetrics extends RumInjectorHealthMetrics
    implements RumTelemetryCollector {
  private static final Logger log = LoggerFactory.getLogger(DefaultRumInjectorHealthMetrics.class);

  private static final String[] NO_TAGS = new String[0];

  private final AtomicBoolean started = new AtomicBoolean(false);
  private volatile AgentTaskScheduler.Scheduled<DefaultRumInjectorHealthMetrics> cancellation;

  private final FixedSizeStripedLongCounter injectionSucceed =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter injectionFailed =
      CountersFactory.createFixedSizeStripedCounter(8);
  private final FixedSizeStripedLongCounter injectionSkipped =
      CountersFactory.createFixedSizeStripedCounter(8);

  private final StatsDClient statsd;
  private final long interval;
  private final TimeUnit units;

  @Override
  public void start() {
    if (started.compareAndSet(false, true)) {
      cancellation =
          AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
              new Flush(), this, interval, interval, units);
    }
  }

  public DefaultRumInjectorHealthMetrics(final StatsDClient statsd) {
    this(statsd, 30, SECONDS);
  }

  public DefaultRumInjectorHealthMetrics(final StatsDClient statsd, long interval, TimeUnit units) {
    this.statsd = statsd;
    this.interval = interval;
    this.units = units;
  }

  @Override
  public void onInjectionSucceed() {
    injectionSucceed.inc();
  }

  @Override
  public void onInjectionFailed() {
    injectionFailed.inc();
  }

  @Override
  public void onInjectionSkipped() {
    injectionSkipped.inc();
  }

  @Override
  public void close() {
    if (null != cancellation) {
      cancellation.cancel();
    }
  }

  @Override
  public String summary() {
    return "injectionSucceed="
        + injectionSucceed.get()
        + "\ninjectionFailed="
        + injectionFailed.get()
        + "\ninjectionSkipped="
        + injectionSkipped.get();
  }

  private static class Flush implements AgentTaskScheduler.Task<DefaultRumInjectorHealthMetrics> {

    private final long[] previousCounts = new long[3]; // one per counter
    private int countIndex;

    @Override
    public void run(DefaultRumInjectorHealthMetrics target) {
      countIndex = -1;
      try {
        reportIfChanged(target.statsd, "rum.injection.succeed", target.injectionSucceed, NO_TAGS);
        reportIfChanged(target.statsd, "rum.injection.failed", target.injectionFailed, NO_TAGS);
        reportIfChanged(target.statsd, "rum.injection.skipped", target.injectionSkipped, NO_TAGS);
      } catch (ArrayIndexOutOfBoundsException e) {
        log.warn(
            "previousCounts array needs resizing to at least {}, was {}",
            countIndex + 1,
            previousCounts.length);
      }
    }

    private void reportIfChanged(
        StatsDClient statsDClient,
        String aspect,
        FixedSizeStripedLongCounter counter,
        String[] tags) {
      long count = counter.get();
      long delta = count - previousCounts[++countIndex];
      if (delta > 0) {
        statsDClient.count(aspect, delta, tags);
        previousCounts[countIndex] = count;
      }
    }
  }
}
