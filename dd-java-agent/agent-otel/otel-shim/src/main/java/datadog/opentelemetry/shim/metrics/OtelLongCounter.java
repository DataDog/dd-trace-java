package datadog.opentelemetry.shim.metrics;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounterBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.ObservableLongCounter;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.context.Context;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OtelLongCounter extends Instrument implements LongCounter {

  private static final Logger LOGGER = LoggerFactory.getLogger(OtelLongCounter.class);
  final OtelMeter otelMeter;

  OtelLongCounter(OtelMeter otelMeter, MetricStreamIdentity metricStreamIdentity) {
    super(metricStreamIdentity);
    LOGGER.info("creating a new OtelLongCounter");
    this.otelMeter = otelMeter;
    /// this.storage = storage;
  }

  @Override
  public void add(long value) {
    if (value < 0) {
      LOGGER.warn(
          "Counters can only increase. Instrument "
              + getMetricStreamIdentity().instrumentName()
              + " has recorded a negative value.");
      return;
    }
    LOGGER.info("(not) adding {} to the OtelLongCounter", value);
  }

  @Override
  public void add(long value, Attributes attributes) {}

  @Override
  public void add(long value, Attributes attributes, Context context) {}

  static class OtelLongCounterBuilder implements LongCounterBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(OtelLongCounterBuilder.class);
    InstrumentBuilder builder;

    public OtelLongCounterBuilder(OtelMeter otelMeter, String instrumentName) {
      this.builder =
          new InstrumentBuilder(
              otelMeter, instrumentName, InstrumentType.COUNTER, InstrumentValueType.LONG);
    }

    public OtelLongCounterBuilder setUnit(String unit) {
      builder.setUnit(unit);
      return this;
    }

    public OtelLongCounterBuilder setDescription(String description) {
      builder.setDescription(description);
      return this;
    }

    @Override
    public DoubleCounterBuilder ofDoubles() {
      return builder.swapBuilder(OtelDoubleCounter.OtelDoubleCounterBuilder::new);
    }

    @Override
    public LongCounter build() {
      return builder.buildSynchronousInstrument(OtelLongCounter::new);
    }

    @Override
    public ObservableLongCounter buildWithCallback(Consumer<ObservableLongMeasurement> consumer) {
      return null;
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
      return LongCounterBuilder.super.buildObserver();
    }
  }
}
