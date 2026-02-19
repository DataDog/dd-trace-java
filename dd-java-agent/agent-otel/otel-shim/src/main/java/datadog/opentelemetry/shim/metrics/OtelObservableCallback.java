package datadog.opentelemetry.shim.metrics;

import datadog.trace.relocate.api.RatelimitedLogger;
import io.opentelemetry.api.metrics.BatchCallback;
import io.opentelemetry.api.metrics.ObservableDoubleCounter;
import io.opentelemetry.api.metrics.ObservableDoubleGauge;
import io.opentelemetry.api.metrics.ObservableDoubleUpDownCounter;
import io.opentelemetry.api.metrics.ObservableLongCounter;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import io.opentelemetry.api.metrics.ObservableLongUpDownCounter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class OtelObservableCallback
    implements ObservableDoubleCounter,
        ObservableLongCounter,
        ObservableDoubleGauge,
        ObservableLongGauge,
        ObservableDoubleUpDownCounter,
        ObservableLongUpDownCounter,
        BatchCallback {

  private static final Logger LOGGER = LoggerFactory.getLogger(OtelObservableCallback.class);
  private static final RatelimitedLogger RATELIMITED_LOGGER =
      new RatelimitedLogger(LOGGER, 5, TimeUnit.MINUTES);

  private final OtelMeter meter;
  private final Runnable callback;
  private final List<OtelObservableMeasurement> measurements;

  OtelObservableCallback(
      OtelMeter meter, Runnable callback, List<OtelObservableMeasurement> measurements) {
    this.meter = meter;
    this.callback = callback;
    this.measurements = measurements;
  }

  void observeMeasurements() {
    measurements.forEach(OtelObservableMeasurement::activate);
    try {
      callback.run();
    } catch (Throwable e) {
      RATELIMITED_LOGGER.warn("An exception occurred invoking callback for {}.", measurements, e);
    } finally {
      measurements.forEach(OtelObservableMeasurement::passivate);
    }
  }

  @Override
  public void close() {
    if (!meter.unregisterObservableCallback(this)) {
      RATELIMITED_LOGGER.warn("Callback for {} has called close() multiple times.", measurements);
    }
  }
}
