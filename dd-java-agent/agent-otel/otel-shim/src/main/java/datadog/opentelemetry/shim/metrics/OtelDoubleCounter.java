package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofDoubles;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.COUNTER;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleCounterBuilder;
import io.opentelemetry.api.metrics.ObservableDoubleCounter;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.context.Context;
import java.util.function.Consumer;

final class OtelDoubleCounter implements DoubleCounter {

  @Override
  public void add(double value) {}

  @Override
  public void add(double value, Attributes attributes) {}

  @Override
  public void add(double value, Attributes attributes, Context context) {}

  static final class Builder implements DoubleCounterBuilder {
    private final OtelInstrumentBuilder instrumentBuilder;

    Builder(OtelInstrumentBuilder instrumentBuilder) {
      this.instrumentBuilder = ofDoubles(instrumentBuilder, COUNTER);
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
      return new OtelDoubleCounter();
    }

    @Override
    public ObservableDoubleCounter buildWithCallback(
        Consumer<ObservableDoubleMeasurement> callback) {
      throw new UnsupportedOperationException("buildWithCallback is not yet supported");
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
      throw new UnsupportedOperationException("buildObserver is not yet supported");
    }
  }
}
