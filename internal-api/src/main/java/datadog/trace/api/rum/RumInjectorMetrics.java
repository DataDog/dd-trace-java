package datadog.trace.api.rum;

import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.StatsDClient;
import datadog.trace.util.AgentTaskScheduler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// This class implements the RumTelemetryCollector interface, which is used to collect telemetry
// from the RumInjector. Metrics are then reported via StatsDClient.
public class RumInjectorMetrics implements RumTelemetryCollector {
  private static final Logger log = LoggerFactory.getLogger(RumInjectorMetrics.class);

  private static final String[] NO_TAGS = new String[0];

  private final AtomicBoolean started = new AtomicBoolean(false);
  private volatile AgentTaskScheduler.Scheduled<RumInjectorMetrics> cancellation;

  private final AtomicLong injectionSucceed = new AtomicLong();
  private final AtomicLong injectionFailed = new AtomicLong();
  private final AtomicLong injectionSkipped = new AtomicLong();
  private final AtomicLong contentSecurityPolicyDetected = new AtomicLong();

  private final StatsDClient statsd;
  private final long interval;
  private final TimeUnit units;

  public void start() {
    if (started.compareAndSet(false, true)) {
      cancellation =
          AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
              new Flush(), this, interval, interval, units);
    }
  }

  public RumInjectorMetrics(final StatsDClient statsd) {
    this(statsd, 30, SECONDS);
  }

  public RumInjectorMetrics(final StatsDClient statsd, long interval, TimeUnit units) {
    this.statsd = statsd;
    this.interval = interval;
    this.units = units;
  }

  @Override
  public void onInjectionSucceed() {
    injectionSucceed.incrementAndGet();
  }

  @Override
  public void onInjectionFailed() {
    injectionFailed.incrementAndGet();
  }

  @Override
  public void onInjectionSkipped() {
    injectionSkipped.incrementAndGet();
  }

  @Override
  public void onContentSecurityPolicyDetected() {
    contentSecurityPolicyDetected.incrementAndGet();
  }

  @Override
  public void onInjectionResponseSize(long bytes) {
    // report distribution metric immediately
    statsd.distribution("rum.injection.response.bytes", bytes, NO_TAGS);
  }

  @Override
  public void onInjectionTime(long milliseconds) {
    // report distribution metric immediately
    statsd.distribution("rum.injection.ms", milliseconds, NO_TAGS);
  }

  public void close() {
    if (null != cancellation) {
      cancellation.cancel();
    }
  }

  public String summary() {
    return "injectionSucceed="
        + injectionSucceed.get()
        + "\ninjectionFailed="
        + injectionFailed.get()
        + "\ninjectionSkipped="
        + injectionSkipped.get()
        + "\ncontentSecurityPolicyDetected="
        + contentSecurityPolicyDetected.get();
  }

  private static class Flush implements AgentTaskScheduler.Task<RumInjectorMetrics> {

    private final long[] previousCounts = new long[4]; // one per counter
    private int countIndex;

    @Override
    public void run(RumInjectorMetrics target) {
      countIndex = -1;
      try {
        reportIfChanged(target.statsd, "rum.injection.succeed", target.injectionSucceed, NO_TAGS);
        reportIfChanged(target.statsd, "rum.injection.failed", target.injectionFailed, NO_TAGS);
        reportIfChanged(target.statsd, "rum.injection.skipped", target.injectionSkipped, NO_TAGS);
        reportIfChanged(
            target.statsd,
            "rum.injection.content_security_policy",
            target.contentSecurityPolicyDetected,
            NO_TAGS);
      } catch (ArrayIndexOutOfBoundsException e) {
        log.warn(
            "previousCounts array needs resizing to at least {}, was {}",
            countIndex + 1,
            previousCounts.length);
      }
    }

    private void reportIfChanged(
        StatsDClient statsDClient, String aspect, AtomicLong counter, String[] tags) {
      long count = counter.get();
      long delta = count - previousCounts[++countIndex];
      if (delta > 0) {
        statsDClient.count(aspect, delta, tags);
        previousCounts[countIndex] = count;
      }
    }
  }
}
