package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofDoubles;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.GAUGE;

import datadog.opentelemetry.shim.metrics.data.OtelMetricStorage;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import io.opentelemetry.api.metrics.LongGaugeBuilder;
import io.opentelemetry.api.metrics.ObservableDoubleGauge;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.context.Context;
import java.util.function.Consumer;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
final class OtelDoubleGauge extends OtelInstrument implements DoubleGauge {
  OtelDoubleGauge(OtelMetricStorage storage) {
    super(storage);
  }

  @Override
  public void set(double value) {
    set(value, Attributes.empty());
  }

  @Override
  public void set(double value, Attributes attributes) {
    storage.recordDouble(value, attributes);
  }

  @Override
  public void set(double value, Attributes attributes, Context unused) {
    set(value, attributes);
  }

  static final class Builder implements DoubleGaugeBuilder {
    private final OtelMeter meter;
    private final OtelInstrumentBuilder builder;

    Builder(OtelMeter meter, String instrumentName) {
      this.meter = meter;
      this.builder = ofDoubles(instrumentName, GAUGE);
    }

    @Override
    public DoubleGaugeBuilder setDescription(String description) {
      builder.setDescription(description);
      return this;
    }

    @Override
    public DoubleGaugeBuilder setUnit(String unit) {
      builder.setUnit(unit);
      return this;
    }

    @Override
    public LongGaugeBuilder ofLongs() {
      return new OtelLongGauge.Builder(meter, builder);
    }

    @Override
    public DoubleGauge build() {
      return new OtelDoubleGauge(
          meter.registerStorage(builder, OtelMetricStorage::newDoubleValueStorage));
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
      return meter.registerObservableStorage(builder, OtelMetricStorage::newDoubleValueStorage);
    }

    @Override
    public ObservableDoubleGauge buildWithCallback(Consumer<ObservableDoubleMeasurement> callback) {
      return meter.registerObservableCallback(callback, buildObserver());
    }
  }
}
