package datadog.opentelemetry.shim.metrics;

abstract class Instrument {
  private final MetricStreamIdentity metricStreamIdentity;

  Instrument(MetricStreamIdentity metricStreamIdentity) {
    this.metricStreamIdentity = metricStreamIdentity;
  }

  final MetricStreamIdentity getMetricStreamIdentity() {
    return metricStreamIdentity;
  }
}
