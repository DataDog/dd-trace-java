package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofLongs;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.GAUGE;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_INSTRUMENT_NAME;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_METER;

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
  private final OtelMetricStorage storage;

  OtelLongGauge(OtelInstrumentDescriptor descriptor) {
    super(descriptor);
    this.storage = OtelMetricStorage.newLongValueStorage(descriptor);
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
    private final OtelInstrumentBuilder instrumentBuilder;

    Builder(OtelInstrumentBuilder builder) {
      this.instrumentBuilder = ofLongs(builder, GAUGE);
    }

    @Override
    public LongGaugeBuilder setDescription(String description) {
      instrumentBuilder.setDescription(description);
      return this;
    }

    @Override
    public LongGaugeBuilder setUnit(String unit) {
      instrumentBuilder.setUnit(unit);
      return this;
    }

    @Override
    public LongGauge build() {
      return new OtelLongGauge(instrumentBuilder.toDescriptor());
    }

    @Override
    public ObservableLongGauge buildWithCallback(Consumer<ObservableLongMeasurement> callback) {
      // FIXME: implement callback
      return NOOP_METER.gaugeBuilder(NOOP_INSTRUMENT_NAME).ofLongs().buildWithCallback(callback);
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
      // FIXME: implement observer
      return NOOP_METER.gaugeBuilder(NOOP_INSTRUMENT_NAME).ofLongs().buildObserver();
    }
  }
}
