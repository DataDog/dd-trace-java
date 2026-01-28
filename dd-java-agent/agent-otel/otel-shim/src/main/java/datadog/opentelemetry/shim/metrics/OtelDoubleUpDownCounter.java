package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofDoubles;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.UP_DOWN_COUNTER;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_INSTRUMENT_NAME;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_METER;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleUpDownCounter;
import io.opentelemetry.api.metrics.DoubleUpDownCounterBuilder;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.api.metrics.ObservableDoubleUpDownCounter;
import io.opentelemetry.context.Context;
import java.util.function.Consumer;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
final class OtelDoubleUpDownCounter extends OtelInstrument implements DoubleUpDownCounter {

  OtelDoubleUpDownCounter(OtelInstrumentDescriptor descriptor) {
    super(descriptor);
  }

  @Override
  public void add(double value) {
    add(value, Attributes.empty());
  }

  @Override
  public void add(double value, Attributes attributes) {
    // FIXME: implement recording
  }

  @Override
  public void add(double value, Attributes attributes, Context unused) {
    add(value, attributes);
  }

  static final class Builder implements DoubleUpDownCounterBuilder {
    private final OtelInstrumentBuilder instrumentBuilder;

    Builder(OtelInstrumentBuilder builder) {
      this.instrumentBuilder = ofDoubles(builder, UP_DOWN_COUNTER);
    }

    @Override
    public DoubleUpDownCounterBuilder setDescription(String description) {
      instrumentBuilder.setDescription(description);
      return this;
    }

    @Override
    public DoubleUpDownCounterBuilder setUnit(String unit) {
      instrumentBuilder.setUnit(unit);
      return this;
    }

    @Override
    public DoubleUpDownCounter build() {
      return new OtelDoubleUpDownCounter(instrumentBuilder.toDescriptor());
    }

    @Override
    public ObservableDoubleUpDownCounter buildWithCallback(
        Consumer<ObservableDoubleMeasurement> callback) {
      // FIXME: implement callback
      return NOOP_METER
          .upDownCounterBuilder(NOOP_INSTRUMENT_NAME)
          .ofDoubles()
          .buildWithCallback(callback);
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
      // FIXME: implement observer
      return NOOP_METER.upDownCounterBuilder(NOOP_INSTRUMENT_NAME).ofDoubles().buildObserver();
    }
  }
}
