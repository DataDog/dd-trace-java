package datadog.trace.bootstrap.otel.metrics;

public enum OtelInstrumentType {
  // same order as io.opentelemetry.sdk.metrics.InstrumentType
  COUNTER(false),
  UP_DOWN_COUNTER(false),
  HISTOGRAM(false),
  OBSERVABLE_COUNTER(true),
  OBSERVABLE_UP_DOWN_COUNTER(true),
  OBSERVABLE_GAUGE(true),
  GAUGE(false);

  private final boolean observable;

  OtelInstrumentType(boolean observable) {
    this.observable = observable;
  }

  public boolean isObservable() {
    return observable;
  }
}
