package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofLongs;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.GAUGE;

import io.opentelemetry.api.metrics.LongGauge;
import io.opentelemetry.api.metrics.LongGaugeBuilder;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import java.util.function.Consumer;

final class OtelLongGauge {
  static final class Builder implements LongGaugeBuilder {
    private final OtelInstrumentBuilder instrumentBuilder;

    Builder(OtelInstrumentBuilder instrumentBuilder) {
      this.instrumentBuilder = ofLongs(instrumentBuilder, GAUGE);
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
      throw new UnsupportedOperationException("build is not yet supported");
    }

    @Override
    public ObservableLongGauge buildWithCallback(Consumer<ObservableLongMeasurement> callback) {
      throw new UnsupportedOperationException("buildWithCallback is not yet supported");
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
      throw new UnsupportedOperationException("buildObserver is not yet supported");
    }
  }
}
