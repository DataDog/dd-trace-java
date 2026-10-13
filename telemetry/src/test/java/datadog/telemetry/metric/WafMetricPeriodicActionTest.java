package datadog.telemetry.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Metric;
import datadog.trace.api.telemetry.WafMetricCollector;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WafMetricPeriodicActionTest {

  private final WafMetricPeriodicAction periodicAction = new WafMetricPeriodicAction();
  private final TelemetryService telemetryService = mock(TelemetryService.class);

  @Test
  void pushWafMetricsIntoTheTelemetryService() {
    WafMetricCollector.get().wafInit("0.0.0", "rules_ver_1", true);
    WafMetricCollector.get().wafUpdates("rules_ver_2", true);
    WafMetricCollector.get().wafUpdates("rules_ver_3", true);

    periodicAction.doIteration(telemetryService);

    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(telemetryService, times(3)).addMetric(captor.capture());
    verifyNoMoreInteractions(telemetryService);

    List<Metric> metrics = captor.getAllValues();
    assertMetric(
        metrics,
        "waf.init",
        1L,
        Arrays.asList("waf_version:0.0.0", "event_rules_version:rules_ver_1", "success:true"));
    assertMetric(
        metrics,
        "waf.updates",
        1L,
        Arrays.asList("waf_version:0.0.0", "event_rules_version:rules_ver_2", "success:true"));
    assertMetric(
        metrics,
        "waf.updates",
        1L,
        Arrays.asList("waf_version:0.0.0", "event_rules_version:rules_ver_3", "success:true"));
  }

  @Test
  void pushWafRequestMetricsAndPushIntoTheTelemetry() {
    WafMetricCollector.get().wafInit("0.0.0", "rules_ver_1", true);
    WafMetricCollector.get().wafRequest(false, false, false, false, false, false, false, false);
    WafMetricCollector.get().wafRequest(true, false, false, false, false, false, false, false);
    WafMetricCollector.get().wafRequest(false, false, false, false, false, false, false, false);
    WafMetricCollector.get().wafRequest(false, true, false, false, false, false, false, false);
    WafMetricCollector.get().wafRequest(false, false, false, false, false, false, false, false);
    WafMetricCollector.get().wafRequest(false, false, false, true, false, false, false, false);
    WafMetricCollector.get().wafRequest(false, false, true, false, false, false, false, false);
    WafMetricCollector.get().wafRequest(false, false, false, false, false, true, false, false);
    WafMetricCollector.get().wafRequest(false, false, false, false, true, false, false, false);
    WafMetricCollector.get().wafRequest(false, false, false, false, false, false, true, false);
    WafMetricCollector.get().prepareMetrics();
    periodicAction.doIteration(telemetryService);

    ArgumentCaptor<Metric> firstBatchCaptor = forClass(Metric.class);
    verify(telemetryService, times(9)).addMetric(firstBatchCaptor.capture());
    verifyNoMoreInteractions(telemetryService);

    List<Metric> firstBatch = firstBatchCaptor.getAllValues();
    assertMetric(firstBatch, "waf.init", 1L, null);
    assertRequestMetric(
        firstBatch,
        3L,
        requestTags("rules_ver_1", false, false, false, false, false, false, false));
    assertRequestMetric(
        firstBatch, 1L, requestTags("rules_ver_1", true, false, false, false, false, false, false));
    assertRequestMetric(
        firstBatch, 1L, requestTags("rules_ver_1", false, true, false, false, false, false, false));
    assertRequestMetric(
        firstBatch, 1L, requestTags("rules_ver_1", false, false, true, false, false, false, false));
    assertRequestMetric(
        firstBatch, 1L, requestTags("rules_ver_1", false, false, false, true, false, false, false));
    assertRequestMetric(
        firstBatch, 1L, requestTags("rules_ver_1", false, false, false, false, true, false, false));
    assertRequestMetric(
        firstBatch, 1L, requestTags("rules_ver_1", false, false, false, false, false, true, false));
    assertRequestMetric(
        firstBatch, 1L, requestTags("rules_ver_1", false, false, false, false, false, false, true));

    clearInvocations(telemetryService);

    // waf.updates happens
    WafMetricCollector.get().wafUpdates("rules_ver_2", true);
    WafMetricCollector.get().wafRequest(false, false, false, false, false, false, false, false);
    WafMetricCollector.get().wafRequest(true, false, false, false, false, false, false, false);
    WafMetricCollector.get().wafRequest(false, true, false, false, false, false, false, false);
    WafMetricCollector.get().wafRequest(false, false, false, true, false, false, false, false);
    WafMetricCollector.get().wafRequest(false, false, true, false, false, false, false, false);
    WafMetricCollector.get().wafRequest(false, false, false, false, false, true, false, false);
    WafMetricCollector.get().wafRequest(false, false, false, false, true, false, false, false);
    WafMetricCollector.get().wafRequest(false, false, false, false, false, false, true, false);
    WafMetricCollector.get().prepareMetrics();
    periodicAction.doIteration(telemetryService);

    // following waf.request have a new event_rules_version tag
    ArgumentCaptor<Metric> secondBatchCaptor = forClass(Metric.class);
    verify(telemetryService, times(9)).addMetric(secondBatchCaptor.capture());
    verifyNoMoreInteractions(telemetryService);

    List<Metric> secondBatch = secondBatchCaptor.getAllValues();
    assertMetric(secondBatch, "waf.updates", 1L, null);
    assertRequestMetric(
        secondBatch,
        1L,
        requestTags("rules_ver_2", false, false, false, false, false, false, false));
    assertRequestMetric(
        secondBatch,
        1L,
        requestTags("rules_ver_2", true, false, false, false, false, false, false));
    assertRequestMetric(
        secondBatch,
        1L,
        requestTags("rules_ver_2", false, true, false, false, false, false, false));
    assertRequestMetric(
        secondBatch,
        1L,
        requestTags("rules_ver_2", false, false, true, false, false, false, false));
    assertRequestMetric(
        secondBatch,
        1L,
        requestTags("rules_ver_2", false, false, false, true, false, false, false));
    assertRequestMetric(
        secondBatch,
        1L,
        requestTags("rules_ver_2", false, false, false, false, true, false, false));
    assertRequestMetric(
        secondBatch,
        1L,
        requestTags("rules_ver_2", false, false, false, false, false, true, false));
    assertRequestMetric(
        secondBatch,
        1L,
        requestTags("rules_ver_2", false, false, false, false, false, false, true));
  }

  private void assertRequestMetric(List<Metric> metrics, long expectedCount, List<String> tags) {
    assertMetric(metrics, "waf.requests", expectedCount, tags);
  }

  /**
   * Metrics with equal tags are aggregated into a single point by production code, but ones with
   * distinct tags land in a HashMap keyed by content, so their relative order in the captured list
   * is not guaranteed. Match by (metric name, tags) rather than by position.
   */
  private void assertMetric(
      List<Metric> metrics, String metricName, long expectedCount, List<String> tags) {
    List<Metric> matches =
        metrics.stream()
            .filter(metric -> metric.getMetric().equals(metricName))
            .filter(metric -> tags == null || tags.equals(metric.getTags()))
            .collect(Collectors.toList());
    assertEquals(1, matches.size(), "expected exactly one match for " + metricName + " " + tags);

    Metric metric = matches.get(0);
    assertEquals("appsec", metric.getNamespace());
    assertEquals(expectedCount, metric.getPoints().get(0).get(1).longValue());
  }

  private List<String> requestTags(
      String rulesVersion,
      boolean ruleTriggered,
      boolean requestBlocked,
      boolean wafError,
      boolean wafTimeout,
      boolean blockFailure,
      boolean rateLimited,
      boolean inputTruncated) {
    return Arrays.asList(
        "waf_version:0.0.0",
        "event_rules_version:" + rulesVersion,
        "rule_triggered:" + ruleTriggered,
        "request_blocked:" + requestBlocked,
        "waf_error:" + wafError,
        "waf_timeout:" + wafTimeout,
        "block_failure:" + blockFailure,
        "rate_limited:" + rateLimited,
        "input_truncated:" + inputTruncated,
        "request_excluded:none");
  }
}
