package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofDoubles;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.GAUGE;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_INSTRUMENT_NAME;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_METER;

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

  OtelDoubleGauge(OtelInstrumentBuilder builder) {
    super(builder.build(OtelMetricStorage::newDoubleValueStorage));
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
    private final OtelInstrumentBuilder instrumentBuilder;

    Builder(OtelMeter meter, String instrumentName) {
      this.instrumentBuilder = ofDoubles(meter, instrumentName, GAUGE);
    }

    @Override
    public DoubleGaugeBuilder setDescription(String description) {
      instrumentBuilder.setDescription(description);
      return this;
    }

    @Override
    public DoubleGaugeBuilder setUnit(String unit) {
      instrumentBuilder.setUnit(unit);
      return this;
    }

    @Override
    public LongGaugeBuilder ofLongs() {
      return new OtelLongGauge.Builder(instrumentBuilder);
    }

    @Override
    public DoubleGauge build() {
      return new OtelDoubleGauge(instrumentBuilder);
    }

    @Override
    public ObservableDoubleGauge buildWithCallback(Consumer<ObservableDoubleMeasurement> callback) {
      // FIXME: implement callback
      return NOOP_METER.gaugeBuilder(NOOP_INSTRUMENT_NAME).buildWithCallback(callback);
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
      // FIXME: implement observer
      return NOOP_METER.gaugeBuilder(NOOP_INSTRUMENT_NAME).buildObserver();
    }
  }
}
