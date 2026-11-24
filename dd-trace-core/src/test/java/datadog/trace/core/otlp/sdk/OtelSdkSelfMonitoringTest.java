package datadog.trace.core.otlp.sdk;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import org.junit.jupiter.api.Test;

class OtelSdkSelfMonitoringTest {
  @Test
  void gateOffReturnsNull() {
    Config config = mock(Config.class);
    when(config.isOtelSdkSelfMonitoringMetricsActive()).thenReturn(false);

    assertNull(OtelSdkSelfMonitoring.healthMetrics(config));
  }

  @Test
  void gateOnReturnsTheOtelHealthMetrics() {
    Config config = mock(Config.class);
    when(config.isOtelSdkSelfMonitoringMetricsActive()).thenReturn(true);

    assertSame(OtelHealthMetrics.INSTANCE, OtelSdkSelfMonitoring.healthMetrics(config));
  }
}
