package datadog.opentelemetry.shim.metrics;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleCounterBuilder;
import io.opentelemetry.api.metrics.ObservableDoubleCounter;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.context.Context;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OtelDoubleCounter extends Instrument implements DoubleCounter {

  private static final Logger LOGGER = LoggerFactory.getLogger(OtelDoubleCounter.class);

  final OtelMeter otelMeter;

  OtelDoubleCounter(OtelMeter otelMeter, MetricStreamIdentity metricStreamIdentity) {
    super(metricStreamIdentity);
    LOGGER.info("creating a new OtelDoubleCounter");
    this.otelMeter = otelMeter;
    /// this.storage = storage;
  }

  @Override
  public void add(double value) {
    if (value < 0) {
      LOGGER.info(
          "Counters can only increase. Instrument "
              + getMetricStreamIdentity().instrumentName()
              + " has recorded a negative value.");
      return;
    }
    LOGGER.info("(not) adding {} to the OtelDoubleCounter", value);
  }

  @Override
  public void add(double value, Attributes attributes) {}

  @Override
  public void add(double value, Attributes attributes, Context context) {}

  static class OtelDoubleCounterBuilder implements DoubleCounterBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(OtelDoubleCounterBuilder.class);
    InstrumentBuilder builder;

    OtelDoubleCounterBuilder(
        OtelMeter otelMeter, String instrumentName, String description, String unit) {
      this.builder =
          new InstrumentBuilder(
                  otelMeter, instrumentName, InstrumentType.COUNTER, InstrumentValueType.DOUBLE)
              .setUnit(unit)
              .setDescription(description);
    }

    @Override
    public DoubleCounterBuilder setDescription(String description) {
      builder.setDescription(description);
      return this;
    }

    @Override
    public DoubleCounterBuilder setUnit(String unit) {
      builder.setUnit(unit);
      return this;
    }

    @Override
    public DoubleCounter build() {
      return builder.buildSynchronousInstrument(OtelDoubleCounter::new);
    }

    @Override
    public ObservableDoubleCounter buildWithCallback(
        Consumer<ObservableDoubleMeasurement> consumer) {
      return null;
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
      return DoubleCounterBuilder.super.buildObserver();
    }
  }
}
