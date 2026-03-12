package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofLongs;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.UP_DOWN_COUNTER;

import datadog.opentelemetry.shim.metrics.data.OtelMetricStorage;
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
  OtelLongUpDownCounter(OtelMetricStorage storage) {
    super(storage);
  }

  @Override
  public void add(long value) {
    add(value, Attributes.empty());
  }

  @Override
  public void add(long value, Attributes attributes) {
    storage.recordLong(value, attributes);
  }

  @Override
  public void add(long value, Attributes attributes, Context unused) {
    add(value, attributes);
  }

  static final class Builder implements LongUpDownCounterBuilder {
    private final OtelMeter meter;
    private final OtelInstrumentBuilder builder;

    Builder(OtelMeter meter, String instrumentName) {
      this.meter = meter;
      this.builder = ofLongs(instrumentName, UP_DOWN_COUNTER);
    }

    @Override
    public LongUpDownCounterBuilder setDescription(String description) {
      builder.setDescription(description);
      return this;
    }

    @Override
    public LongUpDownCounterBuilder setUnit(String unit) {
      builder.setUnit(unit);
      return this;
    }

    @Override
    public DoubleUpDownCounterBuilder ofDoubles() {
      return new OtelDoubleUpDownCounter.Builder(meter, builder);
    }

    @Override
    public LongUpDownCounter build() {
      return new OtelLongUpDownCounter(
          meter.registerStorage(builder, OtelMetricStorage::newLongSumStorage));
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
      return meter.registerObservableStorage(builder, OtelMetricStorage::newLongSumStorage);
    }

    @Override
    public ObservableLongUpDownCounter buildWithCallback(
        Consumer<ObservableLongMeasurement> callback) {
      return meter.registerObservableCallback(callback, buildObserver());
    }
  }
}
