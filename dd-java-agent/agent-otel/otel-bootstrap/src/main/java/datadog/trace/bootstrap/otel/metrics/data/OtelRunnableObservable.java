package datadog.trace.bootstrap.otel.metrics.data;

/** {@link OtelObservable} backed by a {@link Runnable}, for call sites that prefer a lambda. */
public final class OtelRunnableObservable extends OtelObservable {
  private final Runnable callback;

  public OtelRunnableObservable(Runnable callback) {
    this.callback = callback;
  }

  @Override
  protected void observeMeasurements() {
    callback.run();
  }
}
