package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofDoubles;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.COUNTER;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_INSTRUMENT_NAME;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_METER;

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

  OtelDoubleCounter(OtelInstrumentBuilder builder) {
    super(builder.build(OtelMetricStorage::newDoubleSumStorage));
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
          storage.getDescriptor().getName());
    } else {
      storage.recordDouble(value, attributes);
    }
  }

  @Override
  public void add(double value, Attributes attributes, Context unused) {
    add(value, attributes);
  }

  static final class Builder implements DoubleCounterBuilder {
    private final OtelInstrumentBuilder instrumentBuilder;

    Builder(OtelInstrumentBuilder builder) {
      this.instrumentBuilder = ofDoubles(builder, COUNTER);
    }

    @Override
    public DoubleCounterBuilder setDescription(String description) {
      instrumentBuilder.setDescription(description);
      return this;
    }

    @Override
    public DoubleCounterBuilder setUnit(String unit) {
      instrumentBuilder.setUnit(unit);
      return this;
    }

    @Override
    public DoubleCounter build() {
      return new OtelDoubleCounter(instrumentBuilder);
    }

    @Override
    public ObservableDoubleCounter buildWithCallback(
        Consumer<ObservableDoubleMeasurement> callback) {
      // FIXME: implement callback
      return NOOP_METER
          .counterBuilder(NOOP_INSTRUMENT_NAME)
          .ofDoubles()
          .buildWithCallback(callback);
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
      // FIXME: implement observer
      return NOOP_METER.counterBuilder(NOOP_INSTRUMENT_NAME).ofDoubles().buildObserver();
    }
  }
}
