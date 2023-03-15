package com.datadog.appsec.powerwaf;

import datadog.telemetry.api.Metric;
import io.sqreen.powerwaf.Powerwaf;
import io.sqreen.powerwaf.RuleSetInfo;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class MetricCollector {

  public enum AppSecAction {
    NONE,
    TRIGGERED,
    BLOCKED
  }

  static final String NAMESPACE = "appsec";

  private final Queue<Metric> metricsQueue = new ArrayBlockingQueue<>(1024);

  private final AtomicInteger wafUpdateCounter = new AtomicInteger(0);
  private final AtomicRequestCounter wafRequestCounter = new AtomicRequestCounter();
  private final AtomicRequestCounter wafTriggeredRequestCounter = new AtomicRequestCounter();
  private final AtomicRequestCounter wafBlockedRequestCounter = new AtomicRequestCounter();

  // waf.init
  public boolean wafInit(RuleSetInfo ruleSetInfo) {
    return metricsQueue.offer(
            new Metric()
                    .namespace(NAMESPACE)
                    .metric("waf.init")
                    .type(Metric.TypeEnum.COUNT)
                    .common(true)
                    .addPointsItem(
                            Arrays.asList(
                                    System.currentTimeMillis(),
                                    wafUpdateCounter.incrementAndGet()
                            )
                    )
                    .addTagsItem("waf_version:" + Powerwaf.LIB_VERSION)
                    .addTagsItem("event_rules_version:" + ruleSetInfo.fileVersion)
    );
  }

  // waf.updates
  public boolean wafUpdates(RuleSetInfo ruleSetInfo) {
    return metricsQueue.offer(
            new Metric()
                    .namespace(NAMESPACE)
                    .metric("waf.updates")
                    .type(Metric.TypeEnum.COUNT)
                    .common(true)
                    .addPointsItem(
                            Arrays.asList(
                                    System.currentTimeMillis(),
                                    wafUpdateCounter.incrementAndGet()
                            )
                    )
                    .addTagsItem("event_rules_version:" + ruleSetInfo.fileVersion)
    );
  }

  // waf.requests
  public void wafRequest(AppSecAction appSecAction) {
    switch (appSecAction) {
      case NONE:
        wafRequestCounter.increment();
        break;
      case TRIGGERED:
        wafTriggeredRequestCounter.increment();
        break;
      case BLOCKED:
        wafBlockedRequestCounter.increment();
        break;
    }
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
                              Arrays.asList(
                                      wafRequestCounter.getTimestamp(),
                                      wafRequestCounter.getAndReset()
                              )
                      )
                      .addTagsItem("triggered:false")
                      .addTagsItem("blocked:false")
      );
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
                                      wafTriggeredRequestCounter.getAndReset()
                              )
                      )
                      .addTagsItem("triggered:true")
                      .addTagsItem("blocked:false")
      );
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
                                      wafBlockedRequestCounter.getAndReset()
                              )
                      )
                      .addTagsItem("triggered:true")
                      .addTagsItem("blocked:true")
      );
    }
    return metricsQueue;
  }
}
