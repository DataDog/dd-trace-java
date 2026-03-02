package datadog.opentelemetry.shim.metrics;

import datadog.opentelemetry.shim.metrics.data.OtelMetricStorage;
import datadog.trace.relocate.api.RatelimitedLogger;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import java.util.concurrent.TimeUnit;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
final class OtelObservableMeasurement
    implements ObservableDoubleMeasurement, ObservableLongMeasurement {

  private static final Logger LOGGER = LoggerFactory.getLogger(OtelObservableMeasurement.class);
  private static final RatelimitedLogger RATELIMITED_LOGGER =
      new RatelimitedLogger(LOGGER, 5, TimeUnit.MINUTES);

  private final OtelMetricStorage storage;
  private volatile boolean active;

  OtelObservableMeasurement(OtelMetricStorage storage) {
    this.storage = storage;
  }

  void activate() {
    this.active = true;
  }

  void passivate() {
    this.active = false;
  }

  @Override
  public void record(double value) {
    record(value, Attributes.empty());
  }

  @Override
  public void record(double value, Attributes attributes) {
    if (active) {
      storage.recordDouble(value, attributes);
    } else {
      logNotActive();
    }
  }

  @Override
  public void record(long value) {
    record(value, Attributes.empty());
  }

  @Override
  public void record(long value, Attributes attributes) {
    if (active) {
      storage.recordLong(value, attributes);
    } else {
      logNotActive();
    }
  }

  private void logNotActive() {
    if (LOGGER.isDebugEnabled()) {
      RATELIMITED_LOGGER.warn(
          "Measurement recorded for instrument {} outside callback registered to instrument. Dropping measurement.",
          storage.getInstrumentName());
    }
  }
}
