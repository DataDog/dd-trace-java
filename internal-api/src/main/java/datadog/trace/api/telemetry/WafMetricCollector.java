package datadog.trace.api.telemetry;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class WafMetricCollector implements MetricCollector<WafMetricCollector.WafMetric> {

  public static final WafMetricCollector INSTANCE = new WafMetricCollector();

  public static WafMetricCollector get() {
    return WafMetricCollector.INSTANCE;
  }

  private static final String NAMESPACE = "appsec";

  private static final BlockingQueue<WafMetric> rawMetricsQueue =
      new ArrayBlockingQueue<>(RAW_QUEUE_SIZE);

  private static final AtomicInteger wafInitCounter = new AtomicInteger();
  private static final AtomicInteger wafUpdatesCounter = new AtomicInteger();

  private static final AtomicRequestCounter wafRequestCounter = new AtomicRequestCounter();
  private static final AtomicRequestCounter wafTriggeredRequestCounter = new AtomicRequestCounter();
  private static final AtomicRequestCounter wafBlockedRequestCounter = new AtomicRequestCounter();

  /** WAF version that will be initialized with wafInit and reused for all metrics. */
  private static String wafVersion = "";
  /**
   * Rules version that will be updated on each wafInit and wafUpdates. This is not entirely
   * accurate, since wafRequest metrics might be collected for a period where a rules update happens
   * and some requests will be incorrectly reported with the old or new rules version.
   */
  private static String rulesVersion = "";

  public void wafInit(final String wafVersion, final String rulesVersion) {
    WafMetricCollector.wafVersion = wafVersion;
    WafMetricCollector.rulesVersion = rulesVersion;
    rawMetricsQueue.offer(
        new WafInitRawMetric(wafInitCounter.incrementAndGet(), wafVersion, rulesVersion));
  }

  public void wafUpdates(final String rulesVersion) {
    rawMetricsQueue.offer(
        new WafUpdatesRawMetric(wafUpdatesCounter.incrementAndGet(), wafVersion, rulesVersion));

    // Flush request metrics to get the new version.
    if (rulesVersion != null
        && WafMetricCollector.rulesVersion != null
        && !rulesVersion.equals(WafMetricCollector.rulesVersion)) {
      WafMetricCollector.get().prepareMetrics();
    }
    WafMetricCollector.rulesVersion = rulesVersion;
  }

  public void wafRequest() {
    wafRequestCounter.increment();
  }

  public void wafRequestTriggered() {
    wafTriggeredRequestCounter.increment();
  }

  public void wafRequestBlocked() {
    wafBlockedRequestCounter.increment();
  }

  @Override
  public Collection<WafMetric> drain() {
    if (!rawMetricsQueue.isEmpty()) {
      List<WafMetric> list = new LinkedList<>();
      int drained = rawMetricsQueue.drainTo(list);
      if (drained > 0) {
        return list;
      }
    }
    return Collections.emptyList();
  }

  @Override
  public void prepareMetrics() {
    // Requests
    if (wafRequestCounter.get() > 0) {
      if (!rawMetricsQueue.offer(
          new WafRequestsRawMetric(
              wafRequestCounter.getAndReset(),
              WafMetricCollector.wafVersion,
              WafMetricCollector.rulesVersion,
              false,
              false))) {
        return;
      }
    }

    // Triggered requests
    if (wafTriggeredRequestCounter.get() > 0) {
      if (!rawMetricsQueue.offer(
          new WafRequestsRawMetric(
              wafTriggeredRequestCounter.getAndReset(),
              WafMetricCollector.wafVersion,
              WafMetricCollector.rulesVersion,
              true,
              false))) {
        return;
      }
    }

    // Blocked requests
    if (wafBlockedRequestCounter.get() > 0) {
      rawMetricsQueue.offer(
          new WafRequestsRawMetric(
              wafBlockedRequestCounter.getAndReset(),
              WafMetricCollector.wafVersion,
              WafMetricCollector.rulesVersion,
              true,
              true));
    }
  }

  public abstract static class WafMetric extends MetricCollector.Metric {

    public WafMetric(String metricName, long counter, String... tags) {
      super(NAMESPACE, true, metricName, "count", counter, tags);
    }
  }

  public static class WafInitRawMetric extends WafMetric {
    public WafInitRawMetric(
        final long counter, final String wafVersion, final String rulesVersion) {
      super(
          "waf.init", counter, "waf_version:" + wafVersion, "event_rules_version:" + rulesVersion);
    }
  }

  public static class WafUpdatesRawMetric extends WafMetric {
    public WafUpdatesRawMetric(
        final long counter, final String wafVersion, final String rulesVersion) {
      super(
          "waf.updates",
          counter,
          "waf_version:" + wafVersion,
          "event_rules_version:" + rulesVersion);
    }
  }

  public static class WafRequestsRawMetric extends WafMetric {
    public WafRequestsRawMetric(
        final long counter,
        final String wafVersion,
        final String rulesVersion,
        final boolean triggered,
        final boolean blocked) {
      super(
          "waf.requests",
          counter,
          "waf_version:" + wafVersion,
          "event_rules_version:" + rulesVersion,
          "rule_triggered:" + triggered,
          "request_blocked:" + blocked);
    }
  }

  public static class AtomicRequestCounter {

    private final AtomicLong atomicLong = new AtomicLong();
    private volatile long timestamp;

    public final long get() {
      return atomicLong.get();
    }

    public final long getAndReset() {
      timestamp = 0;
      return atomicLong.getAndSet(0);
    }

    public final void increment() {
      if (timestamp == 0) {
        timestamp = System.currentTimeMillis();
      }
      atomicLong.incrementAndGet();
    }
  }
}
