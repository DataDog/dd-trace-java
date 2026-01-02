package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofDoubles;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.COUNTER;

import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleCounterBuilder;
import io.opentelemetry.api.metrics.ObservableDoubleCounter;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import java.util.function.Consumer;

final class OtelDoubleCounter {
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
      throw new UnsupportedOperationException("build is not yet supported");
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
