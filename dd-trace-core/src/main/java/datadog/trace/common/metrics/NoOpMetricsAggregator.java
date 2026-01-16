package datadog.trace.common.metrics;

import static java.lang.Boolean.FALSE;

import datadog.trace.core.CoreSpan;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public final class NoOpMetricsAggregator implements MetricsAggregator {

  public static final NoOpMetricsAggregator INSTANCE = new NoOpMetricsAggregator();

  @Override
  public void start() {}

  @Override
  public boolean report() {
    return false;
  }

  @Override
  public Future<Boolean> forceReport() {
    return CompletableFuture.completedFuture(FALSE);
  }

  @Override
  public boolean publish(List<? extends CoreSpan<?>> trace) {
    return false;
  }

  @Override
  public void close() {}
}
