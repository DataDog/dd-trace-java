package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofLongs;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.COUNTER;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_INSTRUMENT_NAME;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_METER;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounterBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.ObservableLongCounter;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.context.Context;
import java.util.function.Consumer;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
final class OtelLongCounter implements LongCounter {

  @Override
  public void add(long value) {
    // FIXME: implement recording
  }

  @Override
  public void add(long value, Attributes attributes) {
    // FIXME: implement recording
  }

  @Override
  public void add(long value, Attributes attributes, Context context) {
    // FIXME: implement recording
  }

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
      return new OtelLongCounter();
    }

    @Override
    public ObservableLongCounter buildWithCallback(Consumer<ObservableLongMeasurement> callback) {
      // FIXME: implement callback
      return NOOP_METER.counterBuilder(NOOP_INSTRUMENT_NAME).buildWithCallback(callback);
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
      // FIXME: implement observer
      return NOOP_METER.counterBuilder(NOOP_INSTRUMENT_NAME).buildObserver();
    }
  }
}
