package datadog.opentelemetry.shim.metrics;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import io.opentelemetry.api.metrics.LongGaugeBuilder;
import io.opentelemetry.api.metrics.ObservableDoubleGauge;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.context.Context;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OtelDoubleGauge extends Instrument implements DoubleGauge {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelDoubleGauge.class);

  final OtelMeter otelMeter;

  OtelDoubleGauge(OtelMeter otelMeter, MetricStreamIdentity metricStreamIdentity) {
    super(metricStreamIdentity);
    LOGGER.info("creating a new OtelDoubleGauge");
    this.otelMeter = otelMeter;
    /// this.storage = storage;
  }

  @Override
  public void set(double value) {
    LOGGER.info("(not) setting {} in OtelDoubleGauge", value);
  }

  @Override
  public void set(double value, Attributes attributes) {}

  @Override
  public void set(double value, Attributes attributes, Context context) {}

  static class OtelDoubleGaugeBuilder implements DoubleGaugeBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(OtelDoubleGaugeBuilder.class);
    InstrumentBuilder builder;

    public OtelDoubleGaugeBuilder(OtelMeter otelMeter, String instrumentName) {
      this.builder =
          new InstrumentBuilder(
              otelMeter, instrumentName, InstrumentType.GAUGE, InstrumentValueType.DOUBLE);
    }

    // If a unit is not provided or the unit is null, this must be treated as an empty string.
    @Override
    public OtelDoubleGaugeBuilder setUnit(String unit) {
      builder.setUnit(unit);
      return this;
    }

    @Override
    public OtelDoubleGaugeBuilder setDescription(String description) {
      builder.setDescription(description);
      return this;
    }

    @Override
    public LongGaugeBuilder ofLongs() {
      return builder.swapBuilder(OtelLongGauge.OtelLongGaugeBuilder::new);
    }

    @Override
    public ObservableDoubleGauge buildWithCallback(Consumer<ObservableDoubleMeasurement> consumer) {
      return null;
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
      return DoubleGaugeBuilder.super.buildObserver();
    }

    @Override
    public DoubleGauge build() {
      return builder.buildSynchronousInstrument(OtelDoubleGauge::new);
    }
  }
}
