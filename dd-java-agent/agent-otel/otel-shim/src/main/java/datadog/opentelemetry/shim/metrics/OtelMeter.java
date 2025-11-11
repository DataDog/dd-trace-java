package datadog.opentelemetry.shim.metrics;

import io.opentelemetry.api.metrics.BatchCallback;
import io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.LongUpDownCounterBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableMeasurement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// https://www.javadoc.io/doc/io.opentelemetry/opentelemetry-api/1.47.0/io/opentelemetry/api/metrics/Meter.html
public class OtelMeter implements Meter {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelMeter.class);

  private final String instrumentationScopeName;
  private final String schemaUrl;
  private final String instrumentationVersion;

  public OtelMeter(
      String instrumentationScopeName, String schemaUrl, String instrumentationVersion) {
    this.instrumentationScopeName = instrumentationScopeName;
    this.schemaUrl = schemaUrl;
    this.instrumentationVersion = instrumentationVersion;
  }

  public boolean match(
      String instrumentationScopeName, String instrumentationVersion, String schemaUrl) {
    return instrumentationScopeName.equals(this.instrumentationScopeName)
        && schemaUrl.equals(this.schemaUrl)
        && instrumentationVersion.equals(this.instrumentationVersion);
  }

  @Override
  public LongCounterBuilder counterBuilder(String instrumentName) {
    LOGGER.info("CounterBuilder is not yet supported");
    return null;
  }

  @Override
  public LongUpDownCounterBuilder upDownCounterBuilder(String instrumentName) {
    LOGGER.info("upDownCounterBuilder is not yet supported");
    return null;
  }

  @Override
  public DoubleHistogramBuilder histogramBuilder(String instrumentName) {
    LOGGER.info("histogramBuilder is not yet supported");
    return null;
  }

  @Override
  public DoubleGaugeBuilder gaugeBuilder(String instrumentName) {
    LOGGER.info("gaugeBuilder is not yet supported");
    return null;
  }

  @Override
  public BatchCallback batchCallback(
      Runnable callback,
      ObservableMeasurement observableMeasurement,
      ObservableMeasurement... additionalMeasurements) {
    LOGGER.info("batchCallback is not yet supported");
    return Meter.super.batchCallback(callback, observableMeasurement, additionalMeasurements);
  }
}
