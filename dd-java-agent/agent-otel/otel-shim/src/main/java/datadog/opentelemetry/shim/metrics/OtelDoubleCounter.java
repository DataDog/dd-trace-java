package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofDoubles;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.COUNTER;

import datadog.opentelemetry.shim.metrics.data.OtelMetricStorage;
import datadog.trace.relocate.api.RatelimitedLogger;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleCounterBuilder;
import io.opentelemetry.api.metrics.ObservableDoubleCounter;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.context.Context;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
final class OtelDoubleCounter extends OtelInstrument implements DoubleCounter {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelDoubleCounter.class);
  private static final RatelimitedLogger RATELIMITED_LOGGER =
      new RatelimitedLogger(LOGGER, 5, TimeUnit.MINUTES);

  OtelDoubleCounter(OtelMetricStorage storage) {
    super(storage);
  }

  @Override
  public void add(double value) {
    add(value, Attributes.empty());
  }

  @Override
  public void add(double value, Attributes attributes) {
    if (value < 0) {
      RATELIMITED_LOGGER.warn(
          "Counters can only increase. Instrument {} has recorded a negative value.",
          storage.getInstrumentName());
    } else {
      storage.recordDouble(value, attributes);
    }
  }

  @Override
  public void add(double value, Attributes attributes, Context unused) {
    add(value, attributes);
  }

  static final class Builder implements DoubleCounterBuilder {
    private final OtelMeter meter;
    private final OtelInstrumentBuilder builder;

    Builder(OtelMeter meter, OtelInstrumentBuilder builder) {
      this.meter = meter;
      this.builder = ofDoubles(builder, COUNTER);
    }

    @Override
    public DoubleCounterBuilder setDescription(String description) {
      builder.setDescription(description);
      return this;
    }

    @Override
    public DoubleCounterBuilder setUnit(String unit) {
      builder.setUnit(unit);
      return this;
    }

    @Override
    public DoubleCounter build() {
      return new OtelDoubleCounter(
          meter.registerStorage(builder, OtelMetricStorage::newDoubleSumStorage));
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
      return meter.registerObservableStorage(builder, OtelMetricStorage::newDoubleSumStorage);
    }

    @Override
    public ObservableDoubleCounter buildWithCallback(
        Consumer<ObservableDoubleMeasurement> callback) {
      return meter.registerObservableCallback(callback, buildObserver());
    }
  }
}
