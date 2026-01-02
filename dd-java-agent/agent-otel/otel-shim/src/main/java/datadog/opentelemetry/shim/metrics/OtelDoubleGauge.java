package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofDoubles;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.GAUGE;

import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import io.opentelemetry.api.metrics.LongGaugeBuilder;
import io.opentelemetry.api.metrics.ObservableDoubleGauge;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import java.util.function.Consumer;

final class OtelDoubleGauge {
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
      throw new UnsupportedOperationException("build is not yet supported");
    }

    @Override
    public ObservableDoubleGauge buildWithCallback(Consumer<ObservableDoubleMeasurement> callback) {
      throw new UnsupportedOperationException("buildWithCallback is not yet supported");
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
      throw new UnsupportedOperationException("buildObserver is not yet supported");
    }
  }
}
