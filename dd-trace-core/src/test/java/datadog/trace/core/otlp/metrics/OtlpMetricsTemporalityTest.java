package datadog.trace.core.otlp.metrics;

import static datadog.trace.api.config.OtlpConfig.Temporality.CUMULATIVE;
import static datadog.trace.api.config.OtlpConfig.Temporality.DELTA;
import static datadog.trace.api.config.OtlpConfig.Temporality.LOWMEMORY;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.COUNTER;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.GAUGE;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.HISTOGRAM;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.OBSERVABLE_COUNTER;
import static datadog.trace.core.otlp.metrics.OtlpMetricsTemporality.TEMPORALITY_CUMULATIVE;
import static datadog.trace.core.otlp.metrics.OtlpMetricsTemporality.TEMPORALITY_DELTA;
import static datadog.trace.core.otlp.metrics.OtlpMetricsTemporality.temporality;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OtlpMetricsTemporalityTest {

  @Test
  void deltaPreferenceMakesEligibleTypesDelta() {
    assertEquals(TEMPORALITY_DELTA, temporality(DELTA, HISTOGRAM));
    assertEquals(TEMPORALITY_DELTA, temporality(DELTA, COUNTER));
    assertEquals(TEMPORALITY_DELTA, temporality(DELTA, OBSERVABLE_COUNTER));
  }

  @Test
  void deltaPreferenceKeepsIneligibleTypesCumulative() {
    assertEquals(TEMPORALITY_CUMULATIVE, temporality(DELTA, GAUGE));
  }

  @Test
  void lowMemoryPreferenceMakesEligibleTypesDelta() {
    assertEquals(TEMPORALITY_DELTA, temporality(LOWMEMORY, HISTOGRAM));
    assertEquals(TEMPORALITY_DELTA, temporality(LOWMEMORY, COUNTER));
  }

  @Test
  void lowMemoryPreferenceKeepsObservableCounterCumulative() {
    assertEquals(TEMPORALITY_CUMULATIVE, temporality(LOWMEMORY, OBSERVABLE_COUNTER));
  }

  @Test
  void cumulativePreferenceIsAlwaysCumulative() {
    assertEquals(TEMPORALITY_CUMULATIVE, temporality(CUMULATIVE, COUNTER));
  }
}
