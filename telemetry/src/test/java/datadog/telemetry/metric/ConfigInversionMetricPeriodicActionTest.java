package datadog.telemetry.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Metric;
import datadog.trace.api.telemetry.ConfigInversionMetricCollectorImpl;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConfigInversionMetricPeriodicActionTest {

  private final TelemetryService telemetryService = mock(TelemetryService.class);
  private final ConfigInversionMetricPeriodicAction action =
      new ConfigInversionMetricPeriodicAction();
  private final ConfigInversionMetricCollectorImpl collector =
      ConfigInversionMetricCollectorImpl.getInstance();

  @Test
  void testUndocumentedEnvVarMetric() {
    collector.setUndocumentedEnvVarMetric("DD_ENV_VAR");
    collector.prepareMetrics();
    action.doIteration(telemetryService);

    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(telemetryService, times(1)).addMetric(captor.capture());
    verifyNoMoreInteractions(telemetryService);

    Metric metric = captor.getValue();
    assertEquals("tracers", metric.getNamespace());
    assertEquals("untracked.config.detected", metric.getMetric());
    assertEquals(1L, metric.getPoints().get(0).get(1).longValue());
    assertEquals(Arrays.asList("config_name:DD_ENV_VAR"), metric.getTags());
    assertEquals(Metric.TypeEnum.COUNT, metric.getType());
  }
}
