package datadog.opentelemetry.shim.metrics;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleUpDownCounter;
import io.opentelemetry.api.metrics.DoubleUpDownCounterBuilder;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.api.metrics.ObservableDoubleUpDownCounter;
import io.opentelemetry.context.Context;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OtelDoubleUpDownCounter extends Instrument implements DoubleUpDownCounter {

  private static final Logger LOGGER = LoggerFactory.getLogger(OtelDoubleUpDownCounter.class);
  final OtelMeter otelMeter;

  OtelDoubleUpDownCounter(OtelMeter otelMeter, MetricStreamIdentity metricStreamIdentity) {
    super(metricStreamIdentity);
    LOGGER.info("creating a new OtelDoubleUpDownCounter");
    this.otelMeter = otelMeter;
    /// this.storage = storage;
  }

  @Override
  public void add(double value) {
    LOGGER.info("(not) adding {} to the OtelDoubleUpDownCounter", value);
  }

  @Override
  public void add(double value, Attributes attributes) {}

  @Override
  public void add(double value, Attributes attributes, Context context) {}

  static class OtelDoubleUpDownCounterBuilder implements DoubleUpDownCounterBuilder {
    private static final Logger LOGGER =
        LoggerFactory.getLogger(OtelDoubleUpDownCounterBuilder.class);
    InstrumentBuilder builder;

    public OtelDoubleUpDownCounterBuilder(
        OtelMeter otelMeter, String instrumentName, String description, String unit) {
      this.builder =
          new InstrumentBuilder(
                  otelMeter,
                  instrumentName,
                  InstrumentType.UP_DOWN_COUNTER,
                  InstrumentValueType.DOUBLE)
              .setUnit(unit)
              .setDescription(description);
    }

    @Override
    public DoubleUpDownCounterBuilder setDescription(String description) {
      builder.setDescription(description);
      return this;
    }

    @Override
    public DoubleUpDownCounterBuilder setUnit(String unit) {
      builder.setUnit(unit);
      return this;
    }

    @Override
    public DoubleUpDownCounter build() {
      return builder.buildSynchronousInstrument(OtelDoubleUpDownCounter::new);
    }

    @Override
    public ObservableDoubleUpDownCounter buildWithCallback(
        Consumer<ObservableDoubleMeasurement> consumer) {
      return null;
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
      return DoubleUpDownCounterBuilder.super.buildObserver();
    }
  }
}
