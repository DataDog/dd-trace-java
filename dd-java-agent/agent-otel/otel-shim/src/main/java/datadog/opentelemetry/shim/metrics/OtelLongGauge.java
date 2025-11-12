package datadog.opentelemetry.shim.metrics;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongGauge;
import io.opentelemetry.api.metrics.LongGaugeBuilder;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.context.Context;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OtelLongGauge extends Instrument implements LongGauge {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelLongGauge.class);

  final OtelMeter otelMeter;

  OtelLongGauge(OtelMeter otelMeter, MetricStreamIdentity metricStreamIdentity) {
    super(metricStreamIdentity);
    LOGGER.info("creating a new OtelLongGauge");
    this.otelMeter = otelMeter;
    /// this.storage = storage;
  }

  @Override
  public void set(long value) {
    LOGGER.info("(not) setting {} in OtelLongGauge", value);
  }

  @Override
  public void set(long value, Attributes attributes) {}

  @Override
  public void set(long value, Attributes attributes, Context context) {}

  static class OtelLongGaugeBuilder implements LongGaugeBuilder {
    InstrumentBuilder builder;

    OtelLongGaugeBuilder(
        OtelMeter otelMeter, String instrumentName, String description, String unit) {
      this.builder =
          new InstrumentBuilder(
                  otelMeter, instrumentName, InstrumentType.GAUGE, InstrumentValueType.LONG)
              .setUnit(unit)
              .setDescription(description);
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
    public ObservableLongGauge buildWithCallback(Consumer<ObservableLongMeasurement> consumer) {
      return null;
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
      return LongGaugeBuilder.super.buildObserver();
    }

    @Override
    public LongGauge build() {
      return builder.buildSynchronousInstrument(OtelLongGauge::new);
    }
  }
}
