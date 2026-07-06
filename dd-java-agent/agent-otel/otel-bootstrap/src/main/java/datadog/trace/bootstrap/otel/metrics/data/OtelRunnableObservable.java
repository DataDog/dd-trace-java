package datadog.trace.bootstrap.otel.metrics.data;

import datadog.logging.RatelimitedLogger;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link OtelObservable} backed by a {@link Runnable}, for call sites that prefer a lambda. */
public final class OtelRunnableObservable extends OtelObservable {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelRunnableObservable.class);
  private static final RatelimitedLogger RATELIMITED_LOGGER =
      new RatelimitedLogger(LOGGER, 5, TimeUnit.MINUTES);

  private final Runnable callback;

  public OtelRunnableObservable(Runnable callback) {
    this.callback = callback;
  }

  @Override
  protected void observeMeasurements() {
    try {
      callback.run();
    } catch (Throwable e) {
      RATELIMITED_LOGGER.warn("An exception occurred invoking observable callback.", e);
    }
  }
}
