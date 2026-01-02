package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofLongs;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.UP_DOWN_COUNTER;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleUpDownCounterBuilder;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.LongUpDownCounterBuilder;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.api.metrics.ObservableLongUpDownCounter;
import io.opentelemetry.context.Context;
import java.util.function.Consumer;

final class OtelLongUpDownCounter implements LongUpDownCounter {

  @Override
  public void add(long value) {}

  @Override
  public void add(long value, Attributes attributes) {}

  @Override
  public void add(long value, Attributes attributes, Context context) {}

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
      return new OtelLongUpDownCounter();
    }

    @Override
    public ObservableLongUpDownCounter buildWithCallback(
        Consumer<ObservableLongMeasurement> callback) {
      throw new UnsupportedOperationException("buildWithCallback is not yet supported");
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
      throw new UnsupportedOperationException("buildObserver is not yet supported");
    }
  }
}
