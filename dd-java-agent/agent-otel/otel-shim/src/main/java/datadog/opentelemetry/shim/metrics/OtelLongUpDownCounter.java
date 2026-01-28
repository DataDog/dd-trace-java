package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofLongs;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.UP_DOWN_COUNTER;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_INSTRUMENT_NAME;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_METER;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleUpDownCounterBuilder;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.LongUpDownCounterBuilder;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.api.metrics.ObservableLongUpDownCounter;
import io.opentelemetry.context.Context;
import java.util.function.Consumer;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
final class OtelLongUpDownCounter extends OtelInstrument implements LongUpDownCounter {

  OtelLongUpDownCounter(OtelInstrumentDescriptor descriptor) {
    super(descriptor);
  }

  @Override
  public void add(long value) {
    add(value, Attributes.empty());
  }

  @Override
  public void add(long value, Attributes attributes) {
    // FIXME: implement recording
  }

  @Override
  public void add(long value, Attributes attributes, Context unused) {
    add(value, attributes);
  }

  static final class Builder implements LongUpDownCounterBuilder {
    private final OtelInstrumentBuilder instrumentBuilder;

    Builder(OtelMeter meter, String instrumentName) {
      this.instrumentBuilder = ofLongs(meter, instrumentName, UP_DOWN_COUNTER);
    }

    @Override
    public LongUpDownCounterBuilder setDescription(String description) {
      instrumentBuilder.setDescription(description);
      return this;
    }

    @Override
    public LongUpDownCounterBuilder setUnit(String unit) {
      instrumentBuilder.setUnit(unit);
      return this;
    }

    @Override
    public DoubleUpDownCounterBuilder ofDoubles() {
      return new OtelDoubleUpDownCounter.Builder(instrumentBuilder);
    }

    @Override
    public LongUpDownCounter build() {
      return new OtelLongUpDownCounter(instrumentBuilder.toDescriptor());
    }

    @Override
    public ObservableLongUpDownCounter buildWithCallback(
        Consumer<ObservableLongMeasurement> callback) {
      // FIXME: implement callback
      return NOOP_METER.upDownCounterBuilder(NOOP_INSTRUMENT_NAME).buildWithCallback(callback);
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
      // FIXME: implement observer
      return NOOP_METER.upDownCounterBuilder(NOOP_INSTRUMENT_NAME).buildObserver();
    }
  }
}
