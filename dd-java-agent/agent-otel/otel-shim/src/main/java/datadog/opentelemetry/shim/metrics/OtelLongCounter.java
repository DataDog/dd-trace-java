package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofLongs;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.COUNTER;

import io.opentelemetry.api.metrics.DoubleCounterBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.ObservableLongCounter;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import java.util.function.Consumer;

final class OtelLongCounter {
  static final class Builder implements LongCounterBuilder {
    private final OtelInstrumentBuilder instrumentBuilder;

    Builder(OtelMeter meter, String instrumentName) {
      this.instrumentBuilder = ofLongs(meter, instrumentName, COUNTER);
    }

    @Override
    public LongCounterBuilder setDescription(String description) {
      instrumentBuilder.setDescription(description);
      return this;
    }

    @Override
    public LongCounterBuilder setUnit(String unit) {
      instrumentBuilder.setUnit(unit);
      return this;
    }

    @Override
    public DoubleCounterBuilder ofDoubles() {
      return new OtelDoubleCounter.Builder(instrumentBuilder);
    }

    @Override
    public LongCounter build() {
      throw new UnsupportedOperationException("build is not yet supported");
    }

    @Override
    public ObservableLongCounter buildWithCallback(Consumer<ObservableLongMeasurement> callback) {
      throw new UnsupportedOperationException("buildWithCallback is not yet supported");
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
      throw new UnsupportedOperationException("buildObserver is not yet supported");
    }
  }
}
