package datadog.trace.core.otlp.metrics;

import static datadog.trace.api.config.OtlpConfig.Temporality.DELTA;
import static datadog.trace.api.config.OtlpConfig.Temporality.LOWMEMORY;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.COUNTER;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.HISTOGRAM;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.OBSERVABLE_COUNTER;

import datadog.trace.api.Config;
import datadog.trace.api.config.OtlpConfig;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentType;

/** Maps instrument types to OTLP's {@code AggregationTemporality} enum values. */
final class OtlpMetricsTemporality {
  private OtlpMetricsTemporality() {}

  static final int TEMPORALITY_DELTA = 1;
  static final int TEMPORALITY_CUMULATIVE = 2;

  private static final OtlpConfig.Temporality PREFERENCE =
      Config.get().getOtlpMetricsTemporalityPreference();

  static final int COUNTER_TEMPORALITY = temporality(PREFERENCE, COUNTER);
  static final int OBSERVABLE_COUNTER_TEMPORALITY = temporality(PREFERENCE, OBSERVABLE_COUNTER);
  static final int HISTOGRAM_TEMPORALITY = temporality(PREFERENCE, HISTOGRAM);

  static int temporality(OtlpConfig.Temporality preference, OtelInstrumentType type) {
    if (preference == DELTA) {
      // gauges and up/down counters stay as cumulative
      if (type == HISTOGRAM || type == COUNTER || type == OBSERVABLE_COUNTER) {
        return TEMPORALITY_DELTA;
      }
    } else if (preference == LOWMEMORY) {
      // observable counters, gauges, and up/down counters stay as cumulative
      if (type == HISTOGRAM || type == COUNTER) {
        return TEMPORALITY_DELTA;
      }
    }
    return TEMPORALITY_CUMULATIVE;
  }
}
