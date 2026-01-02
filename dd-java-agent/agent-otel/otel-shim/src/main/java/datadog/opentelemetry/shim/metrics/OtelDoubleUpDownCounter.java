package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofDoubles;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.UP_DOWN_COUNTER;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleUpDownCounter;
import io.opentelemetry.api.metrics.DoubleUpDownCounterBuilder;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.api.metrics.ObservableDoubleUpDownCounter;
import io.opentelemetry.context.Context;
import java.util.function.Consumer;

final class OtelDoubleUpDownCounter implements DoubleUpDownCounter {

  @Override
  public void add(double value) {}

  @Override
  public void add(double value, Attributes attributes) {}

  @Override
  public void add(double value, Attributes attributes, Context context) {}

  static final class Builder implements DoubleUpDownCounterBuilder {
    private final OtelInstrumentBuilder instrumentBuilder;

    Builder(OtelInstrumentBuilder instrumentBuilder) {
      this.instrumentBuilder = ofDoubles(instrumentBuilder, UP_DOWN_COUNTER);
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
      return new OtelDoubleUpDownCounter();
    }

    @Override
    public ObservableDoubleUpDownCounter buildWithCallback(
        Consumer<ObservableDoubleMeasurement> callback) {
      throw new UnsupportedOperationException("buildWithCallback is not yet supported");
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
      throw new UnsupportedOperationException("buildObserver is not yet supported");
    }
  }
}
