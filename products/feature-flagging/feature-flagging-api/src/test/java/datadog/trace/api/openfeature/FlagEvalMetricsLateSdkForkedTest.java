package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.metrics.MeterProvider;
import org.junit.jupiter.api.Test;

class FlagEvalMetricsLateSdkForkedTest {
  @Test
  void evaluationBeforeSdkRegistrationDoesNotClaimTheGlobalProvider() {
    GlobalOpenTelemetry.resetForTest();
    final FlagEvalMetrics metrics = new FlagEvalMetrics();
    metrics.record("before", "on", "STATIC", null, null);
    assertFalse(GlobalOpenTelemetry.isSet());

    final OpenTelemetry telemetry = mock(OpenTelemetry.class);
    final MeterProvider provider = mock(MeterProvider.class);
    final MeterBuilder meterBuilder = mock(MeterBuilder.class);
    final Meter meter = mock(Meter.class);
    final LongCounterBuilder counterBuilder = mock(LongCounterBuilder.class);
    final LongCounter counter = mock(LongCounter.class);
    when(telemetry.getMeterProvider()).thenReturn(provider);
    when(provider.meterBuilder("ddtrace.openfeature")).thenReturn(meterBuilder);
    when(meterBuilder.build()).thenReturn(meter);
    when(meter.counterBuilder("feature_flag.evaluations")).thenReturn(counterBuilder);
    when(counterBuilder.setUnit("{evaluation}")).thenReturn(counterBuilder);
    when(counterBuilder.setDescription("Number of feature flag evaluations"))
        .thenReturn(counterBuilder);
    when(counterBuilder.build()).thenReturn(counter);
    try {
      assertDoesNotThrow(() -> GlobalOpenTelemetry.set(telemetry));
      metrics.record("after", "on", "STATIC", null, null);
      verify(counter).add(eq(1L), any(Attributes.class));
    } finally {
      metrics.close();
      GlobalOpenTelemetry.resetForTest();
    }
  }
}
