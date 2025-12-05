package datadog.opentelemetry.shim.metrics;

import datadog.opentelemetry.shim.OtelInstrumentationScope;
import io.opentelemetry.api.metrics.BatchCallback;
import io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.LongUpDownCounterBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableMeasurement;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
final class OtelMeter implements Meter {

  OtelMeter(@SuppressWarnings("unused") OtelInstrumentationScope instrumentationScope) {}

  @Override
  public LongCounterBuilder counterBuilder(String instrumentName) {
    throw new UnsupportedOperationException("counterBuilder is not yet supported");
  }

  @Override
  public LongUpDownCounterBuilder upDownCounterBuilder(String instrumentName) {
    throw new UnsupportedOperationException("upDownCounterBuilder is not yet supported");
  }

  @Override
  public DoubleHistogramBuilder histogramBuilder(String instrumentName) {
    throw new UnsupportedOperationException("histogramBuilder is not yet supported");
  }

  @Override
  public DoubleGaugeBuilder gaugeBuilder(String instrumentName) {
    throw new UnsupportedOperationException("gaugeBuilder is not yet supported");
  }

  @Override
  public BatchCallback batchCallback(
      Runnable callback,
      ObservableMeasurement observableMeasurement,
      ObservableMeasurement... additionalMeasurements) {
    throw new UnsupportedOperationException("batchCallback is not yet supported");
  }
}
