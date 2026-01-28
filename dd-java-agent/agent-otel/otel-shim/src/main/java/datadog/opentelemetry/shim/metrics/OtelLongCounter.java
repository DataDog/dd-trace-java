package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofLongs;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.COUNTER;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_INSTRUMENT_NAME;
import static datadog.opentelemetry.shim.metrics.OtelMeter.NOOP_METER;

import datadog.opentelemetry.shim.metrics.data.OtelMetricStorage;
import datadog.trace.relocate.api.RatelimitedLogger;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounterBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.ObservableLongCounter;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.context.Context;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
final class OtelLongCounter extends OtelInstrument implements LongCounter {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelLongCounter.class);
  private static final RatelimitedLogger RATELIMITED_LOGGER =
      new RatelimitedLogger(LOGGER, 5, TimeUnit.MINUTES);

  private final OtelMetricStorage storage;

  OtelLongCounter(OtelInstrumentDescriptor descriptor) {
    super(descriptor);
    this.storage = OtelMetricStorage.newLongSumStorage(descriptor);
  }

  @Override
  public void add(long value) {
    add(value, Attributes.empty());
  }

  @Override
  public void add(long value, Attributes attributes) {
    if (value < 0) {
      RATELIMITED_LOGGER.warn(
          "Counters can only increase. Instrument {} has recorded a negative value.",
          getDescriptor().getName());
    } else {
      storage.recordLong(value, attributes);
    }
  }

  @Override
  public void add(long value, Attributes attributes, Context unused) {
    add(value, attributes);
  }

  static final class Builder implements LongCounterBuilder {
    private final OtelInstrumentBuilder instrumentBuilder;

    Builder(OtelMeter meter, String instrumentName) {
      this.instrumentBuilder = ofLongs(meter, instrumentName, COUNTER);
    }

    @Override
    public LongCounterBuilder setDescription(String description) {
      instrumentBuilder.setDescription(description);
      return this;
    }

    @Override
    public LongCounterBuilder setUnit(String unit) {
      instrumentBuilder.setUnit(unit);
      return this;
    }

    @Override
    public DoubleCounterBuilder ofDoubles() {
      return new OtelDoubleCounter.Builder(instrumentBuilder);
    }

    @Override
    public LongCounter build() {
      return new OtelLongCounter(instrumentBuilder.toDescriptor());
    }

    @Override
    public ObservableLongCounter buildWithCallback(Consumer<ObservableLongMeasurement> callback) {
      // FIXME: implement callback
      return NOOP_METER.counterBuilder(NOOP_INSTRUMENT_NAME).buildWithCallback(callback);
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
      // FIXME: implement observer
      return NOOP_METER.counterBuilder(NOOP_INSTRUMENT_NAME).buildObserver();
    }
  }
}
