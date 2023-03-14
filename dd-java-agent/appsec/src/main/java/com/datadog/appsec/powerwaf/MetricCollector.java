package com.datadog.appsec.powerwaf;

import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Metric;
import io.sqreen.powerwaf.Powerwaf;
import io.sqreen.powerwaf.PowerwafMetrics;
import io.sqreen.powerwaf.RuleSetInfo;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public class MetricCollector {

  static final String NAMESPACE = "appsec";
  private final TelemetryService telemetryService;

  private final static String wafVersionTag = "waf_version:" + Powerwaf.LIB_VERSION;
  private final AtomicInteger wafUpdateCounter = new AtomicInteger(0);
  private String rulesVersionTag = "";

  private final AtomicInteger wafRequestCounter = new AtomicInteger(0);
  private final AtomicInteger wafTriggeredRequestCounter = new AtomicInteger(0);
  private final AtomicInteger wafBlockedRequestCounter = new AtomicInteger(0);


  public MetricCollector(TelemetryService telemetryService) {
    this.telemetryService = telemetryService;
  }

  private Metric createMetric(String metricName, long counter) {
    long currentTimestamp = System.currentTimeMillis();
    return new Metric()
            .namespace(NAMESPACE)
            .metric(metricName)
            .type(Metric.TypeEnum.COUNT)
            .common(true)
            .addPointsItem(Arrays.asList(currentTimestamp, counter));
  }

  // waf.init
  public boolean wafInit(RuleSetInfo ruleSetInfo) {
    this.rulesVersionTag = "event_rules_version:" + ruleSetInfo.fileVersion;
    Metric metric = createMetric("waf.init", 1)
            .addTagsItem(rulesVersionTag)
            .addTagsItem(wafVersionTag);
    return telemetryService.addMetric(metric);
  }

  // waf.updates
  public boolean wafUpdates(RuleSetInfo ruleSetInfo) {
    this.rulesVersionTag = "event_rules_version:" + ruleSetInfo.fileVersion;
    Metric metric = createMetric("waf.updates", wafUpdateCounter.incrementAndGet())
            .addTagsItem(rulesVersionTag);
    return telemetryService.addMetric(metric);
  }

  // waf.requests
  public boolean wafRequests(PowerwafMetrics metrics) {


    return true;
  }
}
