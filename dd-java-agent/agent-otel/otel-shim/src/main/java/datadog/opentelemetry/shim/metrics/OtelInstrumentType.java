package datadog.opentelemetry.shim.metrics;

public enum OtelInstrumentType {
  // same order as opentelemetry-java
  COUNTER,
  UP_DOWN_COUNTER,
  HISTOGRAM,
  OBSERVABLE_COUNTER,
  OBSERVABLE_UP_DOWN_COUNTER,
  OBSERVABLE_GAUGE,
  GAUGE,
}
