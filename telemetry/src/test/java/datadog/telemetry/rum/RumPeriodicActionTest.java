package datadog.telemetry.rum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Metric;
import datadog.trace.api.rum.RumInjectorMetrics;
import datadog.trace.api.rum.RumTelemetryCollector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RumPeriodicActionTest {

  private final TelemetryService telemetryService = mock(TelemetryService.class);

  @Test
  void pushRumMetricsIntoTheTelemetryService() {
    RumInjectorMetrics metricsCollector = new RumInjectorMetrics();
    metricsCollector.onInjectionSucceed("3");
    metricsCollector.onInjectionFailed("5", "gzip");
    metricsCollector.onInjectionResponseSize("3", 1024);

    RumPeriodicAction periodicAction = new RumPeriodicAction(metricsCollector);

    periodicAction.doIteration(telemetryService);

    ArgumentCaptor<Metric> metricCaptor = ArgumentCaptor.forClass(Metric.class);
    verify(telemetryService, times(2)).addMetric(metricCaptor.capture());
    Metric succeedMetric = metricCaptor.getAllValues().get(0);
    assertEquals("rum", succeedMetric.getNamespace());
    assertEquals("injection.succeed", succeedMetric.getMetric());
    assertEquals(Metric.TypeEnum.COUNT, succeedMetric.getType());

    Metric failedMetric = metricCaptor.getAllValues().get(1);
    assertEquals("rum", failedMetric.getNamespace());
    assertEquals("injection.failed", failedMetric.getMetric());
    assertEquals(Metric.TypeEnum.COUNT, failedMetric.getType());

    ArgumentCaptor<DistributionSeries> distributionCaptor =
        ArgumentCaptor.forClass(DistributionSeries.class);
    verify(telemetryService, times(1)).addDistributionSeries(distributionCaptor.capture());
    DistributionSeries distribution = distributionCaptor.getValue();
    assertEquals("rum", distribution.getNamespace());
    assertEquals("injection.response.bytes", distribution.getMetric());

    verifyNoMoreInteractions(telemetryService);
  }

  @Test
  void pushNothingWhenNoMetricsCollectorIsSet() {
    RumPeriodicAction periodicAction = new RumPeriodicAction(RumTelemetryCollector.NO_OP);

    periodicAction.doIteration(telemetryService);

    verify(telemetryService, never()).addMetric(any());
    verify(telemetryService, never()).addDistributionSeries(any());
    verifyNoMoreInteractions(telemetryService);
  }
}
