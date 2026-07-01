package datadog.trace.bootstrap.otel.metrics.data;

public abstract class OtelObservable {
  protected abstract void observeMeasurements();
}
