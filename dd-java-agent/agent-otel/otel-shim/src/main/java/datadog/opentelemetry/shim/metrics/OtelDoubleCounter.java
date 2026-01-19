package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofDoubles;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.COUNTER;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_INSTRUMENT_NAME;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_METER;

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
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
final class OtelDoubleCounter extends OtelInstrument implements DoubleCounter {
  private static final RatelimitedLogger log =
      new RatelimitedLogger(LoggerFactory.getLogger(OtelDoubleCounter.class), 5, TimeUnit.MINUTES);

  OtelDoubleCounter(OtelInstrumentDescriptor descriptor) {
    super(descriptor);
  }

  @Override
  public void add(double value) {
    add(value, Attributes.empty());
  }

  @Override
  public void add(double value, Attributes attributes) {
    add(value, attributes, Context.current());
  }

  @Override
  public void add(double value, Attributes attributes, Context context) {
    if (value < 0) {
      log.warn(
          "Counters can only increase. Instrument "
              + getDescriptor().getName()
              + " has recorded a negative value.");
    } else {
      // FIXME: implement recording
    }
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
      return new OtelDoubleCounter(instrumentBuilder.toDescriptor());
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
