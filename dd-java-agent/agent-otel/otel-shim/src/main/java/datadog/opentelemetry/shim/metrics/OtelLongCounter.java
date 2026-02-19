package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofLongs;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.COUNTER;

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

  OtelLongCounter(OtelMetricStorage storage) {
    super(storage);
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
          storage.getInstrumentName());
    } else {
      storage.recordLong(value, attributes);
    }
  }

  @Override
  public void add(long value, Attributes attributes, Context unused) {
    add(value, attributes);
  }

  static final class Builder implements LongCounterBuilder {
    private final OtelMeter meter;
    private final OtelInstrumentBuilder builder;

    Builder(OtelMeter meter, String instrumentName) {
      this.meter = meter;
      this.builder = ofLongs(instrumentName, COUNTER);
    }

    @Override
    public LongCounterBuilder setDescription(String description) {
      builder.setDescription(description);
      return this;
    }

    @Override
    public LongCounterBuilder setUnit(String unit) {
      builder.setUnit(unit);
      return this;
    }

    @Override
    public DoubleCounterBuilder ofDoubles() {
      return new OtelDoubleCounter.Builder(meter, builder);
    }

    @Override
    public LongCounter build() {
      return new OtelLongCounter(
          meter.registerStorage(builder, OtelMetricStorage::newLongSumStorage));
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
      return meter.registerObservableStorage(builder, OtelMetricStorage::newLongSumStorage);
    }

    @Override
    public ObservableLongCounter buildWithCallback(Consumer<ObservableLongMeasurement> callback) {
      return meter.registerObservableCallback(callback, buildObserver());
    }
  }
}
