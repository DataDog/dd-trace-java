package datadog.opentelemetry.shim.metrics;

import datadog.opentelemetry.shim.OtelInstrumentationScope;
import datadog.opentelemetry.shim.metrics.data.OtelMetricStorage;
import datadog.opentelemetry.shim.metrics.data.OtelPoint;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.BatchCallback;
import io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.LongUpDownCounterBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.metrics.ObservableMeasurement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
final class OtelMeter implements Meter {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelMeter.class);

  private static final Pattern VALID_INSTRUMENT_NAME_PATTERN =
      Pattern.compile("([A-Za-z])([A-Za-z0-9_\\-./]){0,254}");

  static final Meter NOOP_METER = MeterProvider.noop().get("noop");
  static final String NOOP_INSTRUMENT_NAME = "noop";

  private final OtelInstrumentationScope instrumentationScope;

  private final Map<OtelInstrumentDescriptor, OtelMetricStorage> storage =
      new ConcurrentHashMap<>();

  OtelMeter(OtelInstrumentationScope instrumentationScope) {
    this.instrumentationScope = instrumentationScope;
  }

  @Override
  public LongCounterBuilder counterBuilder(String instrumentName) {
    if (!validInstrumentName(instrumentName)) {
      return NOOP_METER.counterBuilder(NOOP_INSTRUMENT_NAME);
    }
    return new OtelLongCounter.Builder(this, instrumentName);
  }

  @Override
  public LongUpDownCounterBuilder upDownCounterBuilder(String instrumentName) {
    if (!validInstrumentName(instrumentName)) {
      return NOOP_METER.upDownCounterBuilder(NOOP_INSTRUMENT_NAME);
    }
    return new OtelLongUpDownCounter.Builder(this, instrumentName);
  }

  @Override
  public DoubleHistogramBuilder histogramBuilder(String instrumentName) {
    if (!validInstrumentName(instrumentName)) {
      return NOOP_METER.histogramBuilder(NOOP_INSTRUMENT_NAME);
    }
    return new OtelDoubleHistogram.Builder(this, instrumentName);
  }

  @Override
  public DoubleGaugeBuilder gaugeBuilder(String instrumentName) {
    if (!validInstrumentName(instrumentName)) {
      return NOOP_METER.gaugeBuilder(NOOP_INSTRUMENT_NAME);
    }
    return new OtelDoubleGauge.Builder(this, instrumentName);
  }

  @Override
  public BatchCallback batchCallback(
      Runnable callback,
      ObservableMeasurement observableMeasurement,
      ObservableMeasurement... additionalMeasurements) {
    // FIXME: implement callback
    return NOOP_METER.batchCallback(callback, observableMeasurement, additionalMeasurements);
  }

  @Override
  public String toString() {
    return "OtelMeter{instrumentationScope=" + instrumentationScope + "}";
  }

  OtelMetricStorage registerStorage(
      OtelInstrumentDescriptor descriptor,
      Function<OtelInstrumentDescriptor, OtelMetricStorage> storageFactory) {
    return storage.computeIfAbsent(descriptor, storageFactory);
  }

  void collect(BiConsumer<Attributes, OtelPoint> consumer) {
    storage.values().forEach(storage -> storage.collect(consumer));
  }

  private static boolean validInstrumentName(@Nullable String instrumentName) {
    if (instrumentName != null && VALID_INSTRUMENT_NAME_PATTERN.matcher(instrumentName).matches()) {
      return true;
    }

    LOGGER.warn(
        "Instrument name \"{}\" is invalid, returning noop instrument."
            + " Instrument names must consist of 255 or fewer characters"
            + " including alphanumeric, _, ., -, /, and start with a letter.",
        instrumentName);

    return false;
  }
}
