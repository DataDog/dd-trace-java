package datadog.opentelemetry.shim.metrics;

public enum OtelInstrumentType {
  // same order as io.opentelemetry.sdk.metrics.InstrumentType
  COUNTER,
  UP_DOWN_COUNTER,
  HISTOGRAM,
  OBSERVABLE_COUNTER,
  OBSERVABLE_UP_DOWN_COUNTER,
  OBSERVABLE_GAUGE,
  GAUGE,
}
