package datadog.trace.api.telemetry;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.telemetry.ConfigInversionMetricCollectorImpl.ConfigInversionMetric;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigInversionMetricCollectorImplTest extends DDJavaSpecification {

  @Test
  void shouldEmitMetricWhenUnsupportedEnvVarIsUsed() {
    // setup
    ConfigInversionMetricCollectorImpl collector = ConfigInversionMetricCollectorImpl.getInstance();

    // when
    ConfigInversionMetricCollectorTestHelper.checkAndEmitUnsupported("FAKE_ENV_VAR");
    collector.prepareMetrics();
    List<ConfigInversionMetric> metrics = new ArrayList<>(collector.drain());

    // then
    assertEquals(
        1,
        metrics.size(),
        "The following Environment Variables need to be added to metadata/supported-configurations.json.: "
            + metrics
            + "\nSee https://datadoghq.atlassian.net/wiki/spaces/APMINT/pages/5372248222/APM+-+Centralized+Configuration+Config+inversion#dd-trace-java");
    ConfigInversionMetric metric = metrics.get(0);
    assertEquals("count", metric.type);
    assertEquals(1, metric.value.intValue());
    assertEquals("tracers", metric.namespace);
    assertEquals("untracked.config.detected", metric.metricName);
    assertEquals(singletonList("config_name:FAKE_ENV_VAR"), metric.tags);
  }

  @Test
  void shouldNotEmitMetricWhenSupportedEnvVarIsUsed() {
    // setup
    ConfigInversionMetricCollectorImpl collector = ConfigInversionMetricCollectorImpl.getInstance();

    // when
    ConfigInversionMetricCollectorTestHelper.checkAndEmitUnsupported("DD_ENV");
    collector.prepareMetrics();
    List<ConfigInversionMetric> metrics = new ArrayList<>(collector.drain());

    // then
    assertTrue(
        metrics.isEmpty(),
        "The following Environment Variables need to be added to metadata/supported-configurations.json: "
            + metrics);
  }
}
