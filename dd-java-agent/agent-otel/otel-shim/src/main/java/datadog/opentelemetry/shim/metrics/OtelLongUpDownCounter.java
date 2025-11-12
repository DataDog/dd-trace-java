package datadog.opentelemetry.shim.metrics;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleUpDownCounterBuilder;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.LongUpDownCounterBuilder;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.api.metrics.ObservableLongUpDownCounter;
import io.opentelemetry.context.Context;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OtelLongUpDownCounter extends Instrument implements LongUpDownCounter {

  private static final Logger LOGGER = LoggerFactory.getLogger(OtelLongUpDownCounter.class);
  final OtelMeter otelMeter;

  OtelLongUpDownCounter(OtelMeter otelMeter, MetricStreamIdentity metricStreamIdentity) {
    super(metricStreamIdentity);
    LOGGER.info("creating a new OtelLongUpDownCounter");
    this.otelMeter = otelMeter;
    /// this.storage = storage;
  }

  @Override
  public void add(long value) {
    LOGGER.info("(not) adding {} to the OtelLongUpDownCounter", value);
  }

  @Override
  public void add(long value, Attributes attributes) {}

  @Override
  public void add(long value, Attributes attributes, Context context) {}

  static class OtelLongUpDownCounterBuilder implements LongUpDownCounterBuilder {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(OtelLongUpDownCounterBuilder.class);
    InstrumentBuilder builder;

    public OtelLongUpDownCounterBuilder(OtelMeter otelMeter, String instrumentName) {
      this.builder =
          new InstrumentBuilder(
              otelMeter, instrumentName, InstrumentType.UP_DOWN_COUNTER, InstrumentValueType.LONG);
    }

    @Override
    public OtelLongUpDownCounterBuilder setUnit(String unit) {
      builder.setUnit(unit);
      return this;
    }

    @Override
    public OtelLongUpDownCounterBuilder setDescription(String description) {
      builder.setDescription(description);
      return this;
    }

    @Override
    public DoubleUpDownCounterBuilder ofDoubles() {
      return builder.swapBuilder(OtelDoubleUpDownCounter.OtelDoubleUpDownCounterBuilder::new);
    }

    @Override
    public LongUpDownCounter build() {
      return builder.buildSynchronousInstrument(OtelLongUpDownCounter::new);
    }

    @Override
    public ObservableLongUpDownCounter buildWithCallback(
        Consumer<ObservableLongMeasurement> consumer) {
      return null;
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
      return LongUpDownCounterBuilder.super.buildObserver();
    }
  }
}
