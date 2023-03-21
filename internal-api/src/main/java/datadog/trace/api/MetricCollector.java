package datadog.trace.api;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MetricCollector {

  public static class Holder {
    public static final MetricCollector INSTANCE = new MetricCollector();
  }

  public static MetricCollector get() {
    return MetricCollector.Holder.INSTANCE;
  }

  private static final String NAMESPACE = "appsec";

  private static final BlockingQueue<RawMetric> rawMetricsQueue = new ArrayBlockingQueue<>(1024);


  private static final AtomicInteger wafInitCounter = new AtomicInteger();
  private static final AtomicInteger wafUpdatesCounter = new AtomicInteger();

  private static final AtomicRequestCounter wafRequestCounter = new AtomicRequestCounter();
  private static final AtomicRequestCounter wafTriggeredRequestCounter = new AtomicRequestCounter();
  private static final AtomicRequestCounter wafBlockedRequestCounter = new AtomicRequestCounter();

  public boolean wafInit(String wafVersion, String rulesVersion) {
    return rawMetricsQueue.offer(new WafInitRawMetric(wafInitCounter.incrementAndGet(), wafVersion, rulesVersion));
  }

  public boolean wafUpdates(String rulesVersion) {
    return rawMetricsQueue.offer(new WafUpdatesRawMetric(wafUpdatesCounter.incrementAndGet(), rulesVersion));
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

  public Collection<RawMetric> drain() {
    if (!prepareRequestMetrics()) {
      return Collections.emptyList();
    }

    if (rawMetricsQueue.isEmpty()) {
      return Collections.emptyList();
    }

    List<RawMetric> list = new LinkedList<>();
    int drained = rawMetricsQueue.drainTo(list);
    if (drained > 0) {
      return list;
    }
    return Collections.emptyList();
  }

  private boolean prepareRequestMetrics() {
    // Requests
    if (wafRequestCounter.get() > 0) {
      if (!rawMetricsQueue.offer(new WafRequestsRawMetric(wafRequestCounter.getAndReset(), false, false))) {
        return false;
      }
    }

    // Triggered requests
    if (wafTriggeredRequestCounter.get() > 0) {
      if (!rawMetricsQueue.offer(new WafRequestsRawMetric(wafTriggeredRequestCounter.getAndReset(), true, false))) {
        return false;
      }
    }

    // Blocked requests
    if (wafBlockedRequestCounter.get() > 0) {
      return rawMetricsQueue.offer(new WafRequestsRawMetric(wafBlockedRequestCounter.getAndReset(), true, true));
    }
    return true;
  }



  public static class RawMetric {
    public final String metricName;
    public final long timestamp;
    public final long counter;
    public final String namespace;

    public RawMetric(String metricName, long counter) {
      this.metricName = metricName;
      this.timestamp = System.currentTimeMillis();
      this.counter = counter;
      this.namespace = NAMESPACE;
    }
  }

  public static class WafInitRawMetric extends RawMetric {
    public final String wafVersion;
    public final String rulesVersion;

    public WafInitRawMetric(long counter, String wafVersion, String rulesVersion) {
      super("waf.init", counter);
      this.wafVersion = wafVersion;
      this.rulesVersion = rulesVersion;
    }
  }

  public static class WafUpdatesRawMetric extends RawMetric {
    public final String rulesVersion;

    public WafUpdatesRawMetric(long counter, String rulesVersion) {
      super("waf.updates", counter);
      this.rulesVersion = rulesVersion;
    }
  }

  public static class WafRequestsRawMetric extends RawMetric {
    public final boolean triggered;
    public final boolean blocked;

    public WafRequestsRawMetric(long counter, boolean triggered, boolean blocked) {
      super("waf.requests", counter);
      this.triggered = triggered;
      this.blocked = blocked;
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

    public final long getTimestamp() {
      return timestamp;
    }
  }
}
