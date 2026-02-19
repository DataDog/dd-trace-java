package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofLongs;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.GAUGE;

import datadog.opentelemetry.shim.metrics.data.OtelMetricStorage;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongGauge;
import io.opentelemetry.api.metrics.LongGaugeBuilder;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.context.Context;
import java.util.function.Consumer;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
final class OtelLongGauge extends OtelInstrument implements LongGauge {
  OtelLongGauge(OtelMetricStorage storage) {
    super(storage);
  }

  @Override
  public void set(long value) {
    set(value, Attributes.empty());
  }

  @Override
  public void set(long value, Attributes attributes) {
    storage.recordLong(value, attributes);
  }

  @Override
  public void set(long value, Attributes attributes, Context unused) {
    set(value, attributes);
  }

  static final class Builder implements LongGaugeBuilder {
    private final OtelMeter meter;
    private final OtelInstrumentBuilder builder;

    Builder(OtelMeter meter, OtelInstrumentBuilder builder) {
      this.meter = meter;
      this.builder = ofLongs(builder, GAUGE);
    }

    @Override
    public LongGaugeBuilder setDescription(String description) {
      builder.setDescription(description);
      return this;
    }

    @Override
    public LongGaugeBuilder setUnit(String unit) {
      builder.setUnit(unit);
      return this;
    }

    @Override
    public LongGauge build() {
      return new OtelLongGauge(
          meter.registerStorage(builder, OtelMetricStorage::newLongValueStorage));
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
      return meter.registerObservableStorage(builder, OtelMetricStorage::newLongValueStorage);
    }

    @Override
    public ObservableLongGauge buildWithCallback(Consumer<ObservableLongMeasurement> callback) {
      return meter.registerObservableCallback(callback, buildObserver());
    }
  }
}
