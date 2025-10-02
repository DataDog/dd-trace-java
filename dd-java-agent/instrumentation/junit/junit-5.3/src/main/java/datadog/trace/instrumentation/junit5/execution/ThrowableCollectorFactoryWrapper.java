package datadog.trace.instrumentation.junit5.execution;

import org.junit.platform.engine.support.hierarchical.ThrowableCollector;

public class ThrowableCollectorFactoryWrapper implements ThrowableCollector.Factory {

  private final ThrowableCollector.Factory delegate;
  private final ThreadLocal<Boolean> suppressFailures = ThreadLocal.withInitial(() -> false);
  private final ThreadLocal<ThrowableCollector> collector = new ThreadLocal<>();

  public ThrowableCollectorFactoryWrapper(ThrowableCollector.Factory delegate) {
    this.delegate = delegate;
  }

  public void setSuppressFailures(boolean suppressFailures) {
    this.suppressFailures.set(suppressFailures);
  }

  public ThrowableCollector getCollector() {
    return collector.get();
  }

  @Override
  public ThrowableCollector create() {
    /*
     * If suppressFailures is set, treat every exception as an assumption error,
     * so that when asked for test execution status,
     * "aborted" is returned instead of "failed".
     * This is needed to avoid failing the build,
     * since the build will fail as long as there are failed tests.
     */
    ThrowableCollector throwableCollector =
        suppressFailures.get() ? new ThrowableCollector(t -> true) : delegate.create();
    collector.set(throwableCollector);
    return throwableCollector;
  }
}
