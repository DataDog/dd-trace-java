package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofDoubles;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.COUNTER;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_INSTRUMENT_NAME;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_METER;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleCounterBuilder;
import io.opentelemetry.api.metrics.ObservableDoubleCounter;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.context.Context;
import java.util.function.Consumer;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
final class OtelDoubleCounter extends OtelInstrument implements DoubleCounter {

  OtelDoubleCounter(OtelInstrumentDescriptor descriptor) {
    super(descriptor);
  }

  @Override
  public void add(double value) {
    // FIXME: implement recording
  }

  @Override
  public void add(double value, Attributes attributes) {
    // FIXME: implement recording
  }

  @Override
  public void add(double value, Attributes attributes, Context context) {
    // FIXME: implement recording
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
