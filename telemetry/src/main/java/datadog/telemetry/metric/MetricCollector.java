package datadog.telemetry.metric;

import datadog.telemetry.api.Metric;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class MetricCollector {

  public static class Holder {
    public static final MetricCollector INSTANCE = new MetricCollector();
  }

  public static MetricCollector get() {
    return MetricCollector.Holder.INSTANCE;
  }

  private static final String NAMESPACE = "appsec";

  private static final BlockingQueue<Metric> metricsQueue = new ArrayBlockingQueue<>(1024);

  private static final AtomicInteger wafUpdateCounter = new AtomicInteger(0);
  private static final AtomicRequestCounter wafRequestCounter = new AtomicRequestCounter();
  private static final AtomicRequestCounter wafTriggeredRequestCounter = new AtomicRequestCounter();
  private static final AtomicRequestCounter wafBlockedRequestCounter = new AtomicRequestCounter();

  // waf.init
  public boolean wafInit(String wafVersion, String rulesVersion) {
    return metricsQueue.offer(
        new Metric()
            .namespace(NAMESPACE)
            .metric("waf.init")
            .type(Metric.TypeEnum.COUNT)
            .common(true)
            .addPointsItem(
                Arrays.asList(System.currentTimeMillis(), wafUpdateCounter.incrementAndGet()))
            .addTagsItem("waf_version:" + wafVersion)
            .addTagsItem("event_rules_version:" + rulesVersion));
  }

  // waf.updates
  public boolean wafUpdates(String rulesVersion) {
    return metricsQueue.offer(
        new Metric()
            .namespace(NAMESPACE)
            .metric("waf.updates")
            .type(Metric.TypeEnum.COUNT)
            .common(true)
            .addPointsItem(
                Arrays.asList(System.currentTimeMillis(), wafUpdateCounter.incrementAndGet()))
            .addTagsItem("event_rules_version:" + rulesVersion));
  }

  // waf.requests
  public void wafRequest() {
    wafRequestCounter.increment();
  }

  // waf.requests (triggered)
  public void wafRequesTriggered() {
    wafTriggeredRequestCounter.increment();
  }

  // waf.requests (blocked)
  public void wafRequestBlocked() {
    wafBlockedRequestCounter.increment();
  }

  public Queue<Metric> prepareMetrics() {

    // Requests
    if (wafRequestCounter.get() > 0) {
      metricsQueue.offer(
          new Metric()
              .namespace(NAMESPACE)
              .metric("waf.requests")
              .type(Metric.TypeEnum.COUNT)
              .common(true)
              .addPointsItem(
                  Arrays.asList(wafRequestCounter.getTimestamp(), wafRequestCounter.getAndReset()))
              .addTagsItem("triggered:false")
              .addTagsItem("blocked:false"));
    }

    // Triggered requests
    if (wafTriggeredRequestCounter.get() > 0) {
      metricsQueue.offer(
          new Metric()
              .namespace(NAMESPACE)
              .metric("waf.requests")
              .type(Metric.TypeEnum.COUNT)
              .common(true)
              .addPointsItem(
                  Arrays.asList(
                      wafTriggeredRequestCounter.getTimestamp(),
                      wafTriggeredRequestCounter.getAndReset()))
              .addTagsItem("triggered:true")
              .addTagsItem("blocked:false"));
    }

    // Blocked requests
    if (wafBlockedRequestCounter.get() > 0) {
      metricsQueue.offer(
          new Metric()
              .namespace(NAMESPACE)
              .metric("waf.requests")
              .type(Metric.TypeEnum.COUNT)
              .common(true)
              .addPointsItem(
                  Arrays.asList(
                      wafBlockedRequestCounter.getTimestamp(),
                      wafBlockedRequestCounter.getAndReset()))
              .addTagsItem("triggered:true")
              .addTagsItem("blocked:true"));
    }
    return metricsQueue;
  }

  public Collection<Metric> drain() {
    prepareMetrics();
    List<Metric> list = new LinkedList<>();
    int drained = metricsQueue.drainTo(list);
    if (drained > 0) {
      return list;
    }
    return Collections.emptyList();
  }
}
