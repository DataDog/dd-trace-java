package datadog.trace.api.rum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.telemetry.MetricCollector.DistributionSeriesPoint;
import datadog.trace.api.telemetry.MetricCollector.Metric;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class RumInjectorMetricsTest {

  private final RumInjectorMetrics metrics = new RumInjectorMetrics();

  // Note: application_id and remote_config_used tags need dynamic runtime values that depend on
  // the RUM configuration state, so we do not test them here.
  @Test
  void testOnInjectionSucceed() {
    // when
    metrics.onInjectionSucceed("3");
    metrics.onInjectionSucceed("5");
    metrics.onInjectionSucceed("6");

    // then
    List<Metric> drained = new ArrayList<>(metrics.drain());
    assertEquals(3, drained.size());

    assertCountMetric(
        drained.get(0), "injection.succeed", "integration_name:servlet", "integration_version:3");
    assertCountMetric(
        drained.get(1), "injection.succeed", "integration_name:servlet", "integration_version:5");
    assertCountMetric(
        drained.get(2), "injection.succeed", "integration_name:servlet", "integration_version:6");
  }

  @Test
  void testOnInjectionFailed() {
    // when
    metrics.onInjectionFailed("3", "gzip");
    metrics.onInjectionFailed("5", null);
    metrics.onInjectionFailed("6", "gzip");

    // then
    List<Metric> drained = new ArrayList<>(metrics.drain());
    assertEquals(3, drained.size());

    assertCountMetric(
        drained.get(0),
        "injection.failed",
        "content_encoding:gzip",
        "integration_name:servlet",
        "integration_version:3",
        "reason:failed_to_return_response_wrapper");

    Metric servlet5 = drained.get(1);
    assertCountMetric(
        servlet5,
        "injection.failed",
        "integration_name:servlet",
        "integration_version:5",
        "reason:failed_to_return_response_wrapper");
    assertFalse(
        servlet5.tags.stream().anyMatch(tag -> tag.startsWith("content_encoding:")),
        "Unexpected content_encoding tag in " + servlet5.tags);

    assertCountMetric(
        drained.get(2),
        "injection.failed",
        "content_encoding:gzip",
        "integration_name:servlet",
        "integration_version:6",
        "reason:failed_to_return_response_wrapper");
  }

  @Test
  void testOnInjectionSkipped() {
    // when
    metrics.onInjectionSkipped("3");
    metrics.onInjectionSkipped("5");
    metrics.onInjectionSkipped("6");

    // then
    List<Metric> drained = new ArrayList<>(metrics.drain());
    assertEquals(3, drained.size());

    assertCountMetric(
        drained.get(0),
        "injection.skipped",
        "integration_name:servlet",
        "integration_version:3",
        "reason:should_not_inject");
    assertCountMetric(
        drained.get(1),
        "injection.skipped",
        "integration_name:servlet",
        "integration_version:5",
        "reason:should_not_inject");
    assertCountMetric(
        drained.get(2),
        "injection.skipped",
        "integration_name:servlet",
        "integration_version:6",
        "reason:should_not_inject");
  }

  @Test
  void testOnInitializationSucceed() {
    // when
    metrics.onInitializationSucceed();

    // then
    List<Metric> drained = new ArrayList<>(metrics.drain());
    assertEquals(1, drained.size());

    assertCountMetric(
        drained.get(0),
        "injection.initialization.succeed",
        "integration_name:servlet",
        "integration_version:N/A");
  }

  @Test
  void testOnContentSecurityPolicyDetected() {
    // when
    metrics.onContentSecurityPolicyDetected("5");

    // then
    List<Metric> drained = new ArrayList<>(metrics.drain());
    assertEquals(1, drained.size());

    assertCountMetric(
        drained.get(0),
        "injection.content_security_policy",
        "integration_name:servlet",
        "integration_version:5",
        "kind:header",
        "reason:csp_header_found",
        "status:seen");
  }

  @Test
  void testOnInjectionResponseSize() {
    // when
    metrics.onInjectionResponseSize("3", 1024);
    metrics.onInjectionResponseSize("5", 2048);

    // then
    List<DistributionSeriesPoint> drained = new ArrayList<>(metrics.drainDistributionSeries());
    assertEquals(2, drained.size());

    assertDistribution(
        drained.get(0),
        "injection.response.bytes",
        1024,
        "integration_name:servlet",
        "integration_version:3",
        "response_kind:header");
    assertDistribution(
        drained.get(1),
        "injection.response.bytes",
        2048,
        "integration_name:servlet",
        "integration_version:5",
        "response_kind:header");
  }

  @Test
  void testOnInjectionTime() {
    // when
    metrics.onInjectionTime("3", 15L);
    metrics.onInjectionTime("5", 25L);

    // then
    List<DistributionSeriesPoint> drained = new ArrayList<>(metrics.drainDistributionSeries());
    assertEquals(2, drained.size());

    assertDistribution(
        drained.get(0), "injection.ms", 15, "integration_name:servlet", "integration_version:3");
    assertDistribution(
        drained.get(1), "injection.ms", 25, "integration_name:servlet", "integration_version:5");
  }

  @Test
  void testDrainMethods() {
    // when
    metrics.onInjectionSucceed("3");
    metrics.onInjectionTime("3", 10L);

    Collection<Metric> drainedData = metrics.drain();
    Collection<DistributionSeriesPoint> data = metrics.drainDistributionSeries();

    Collection<Metric> drainedEmpty = metrics.drain();
    Collection<DistributionSeriesPoint> empty = metrics.drainDistributionSeries();

    // then
    assertEquals(1, drainedData.size());
    assertEquals(1, data.size());
    assertEquals(0, drainedEmpty.size());
    assertEquals(0, empty.size());
  }

  @Test
  void testMixedMetrics() {
    // when
    metrics.onInjectionSucceed("3");
    metrics.onInjectionFailed("4", "gzip");
    metrics.onInjectionResponseSize("5", 512);
    metrics.onInjectionTime("6", 20L);
    metrics.onContentSecurityPolicyDetected("3");

    Collection<Metric> counts = metrics.drain();
    Collection<DistributionSeriesPoint> distributions = metrics.drainDistributionSeries();

    // then
    assertEquals(3, counts.size());
    assertEquals(2, distributions.size());
  }

  @Test
  void testCloseClearsQueues() {
    // when
    metrics.onInjectionSucceed("3");
    metrics.onInjectionTime("5", 10L);

    boolean hasData = !metrics.drain().isEmpty() || !metrics.drainDistributionSeries().isEmpty();

    metrics.close();

    Collection<Metric> emptyCounts = metrics.drain();
    Collection<DistributionSeriesPoint> emptyDistributions = metrics.drainDistributionSeries();

    // then
    assertTrue(hasData);
    assertTrue(emptyCounts.isEmpty());
    assertTrue(emptyDistributions.isEmpty());
  }

  private static void assertCountMetric(
      Metric metric, String expectedMetricName, String... expectedTags) {
    assertEquals("rum", metric.namespace);
    assertEquals(expectedMetricName, metric.metricName);
    assertEquals("count", metric.type);
    assertEquals(1L, metric.value.longValue());
    assertTrue(metric.common);
    assertContainsTags(metric.tags, expectedTags);
  }

  private static void assertDistribution(
      DistributionSeriesPoint point,
      String expectedMetricName,
      int expectedValue,
      String... expectedTags) {
    assertEquals("rum", point.namespace);
    assertEquals(expectedMetricName, point.metricName);
    assertEquals(expectedValue, point.value);
    assertTrue(point.common);
    assertContainsTags(point.tags, expectedTags);
  }

  private static void assertContainsTags(List<String> tags, String... expectedTags) {
    for (String expectedTag : expectedTags) {
      assertTrue(tags.contains(expectedTag), "Missing tag " + expectedTag + " in " + tags);
    }
  }
}
