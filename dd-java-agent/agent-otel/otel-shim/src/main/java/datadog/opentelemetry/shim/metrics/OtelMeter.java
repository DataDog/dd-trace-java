package datadog.opentelemetry.shim.metrics;

import io.opentelemetry.api.metrics.BatchCallback;
import io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.LongUpDownCounterBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.metrics.ObservableMeasurement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// https://www.javadoc.io/doc/io.opentelemetry/opentelemetry-api/1.47.0/io/opentelemetry/api/metrics/Meter.html
public class OtelMeter implements Meter {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelMeter.class);
  private static final Meter NOOP_METER = MeterProvider.noop().get("noop");
  private static final String NOOP_INSTRUMENT_NAME = "noop";

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
    LOGGER.info("CounterBuilder is supported");
    if (isInstrumentNameInvalid(instrumentName)) {
      return NOOP_METER.counterBuilder(NOOP_INSTRUMENT_NAME);
    }
    return new OtelLongCounter.OtelLongCounterBuilder(this, instrumentName);
  }

  @Override
  public LongUpDownCounterBuilder upDownCounterBuilder(String instrumentName) {
    LOGGER.info("upDownCounterBuilder is supported");
    if (isInstrumentNameInvalid(instrumentName)) {
      return NOOP_METER.upDownCounterBuilder(NOOP_INSTRUMENT_NAME);
    }
    return new OtelLongUpDownCounter.OtelLongUpDownCounterBuilder(this, instrumentName);
  }

  @Override
  public DoubleHistogramBuilder histogramBuilder(String instrumentName) {
    LOGGER.info("histogramBuilder is supported");
    if (isInstrumentNameInvalid(instrumentName)) {
      return NOOP_METER.histogramBuilder(NOOP_INSTRUMENT_NAME);
    }
    return new OtelDoubleHistogram.OtelDoubleHistogramBuilder(this, instrumentName);
  }

  @Override
  public DoubleGaugeBuilder gaugeBuilder(String instrumentName) {
    LOGGER.info("gaugeBuilder is supported");
    if (isInstrumentNameInvalid(instrumentName)) {
      return NOOP_METER.gaugeBuilder(NOOP_INSTRUMENT_NAME);
    }
    return new OtelDoubleGauge.OtelDoubleGaugeBuilder(this, instrumentName);
  }

  @Override
  public BatchCallback batchCallback(
      Runnable callback,
      ObservableMeasurement observableMeasurement,
      ObservableMeasurement... additionalMeasurements) {
    LOGGER.info("batchCallback is not yet supported");
    return Meter.super.batchCallback(callback, observableMeasurement, additionalMeasurements);
  }

  private static boolean isInstrumentNameInvalid(String instrumentName) {
    if ((null == instrumentName)
        || instrumentName.isEmpty()
        || instrumentName.length() > 255
        || !Character.isLetter(instrumentName.charAt(0))
        || HasAnyInvalidCharacter(instrumentName)) {
      LOGGER.warn(
          "Invalid instrument name {}. Instrument names must be ASCII, start with letter, contain only alphanumeric characters, '_', '.', '/', '-' and be max 255 characters.",
          instrumentName);
      return true;
    }
    return false;
  }

  private static boolean HasAnyInvalidCharacter(String instrumentName) {
    for (int i = 1; i < instrumentName.length(); i++) {
      char c = instrumentName.charAt(i);
      if (!Character.isLetterOrDigit(c) && c != '_' && c != '.' && c != '/' && c != '-') {
        return true;
      }
    }
    return false;
  }
}
