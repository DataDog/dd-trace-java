package datadog.trace.bootstrap.otel.metrics.data;

import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricsVisitor;
import datadog.trace.bootstrap.otlp.metrics.OtlpScopedMetricsVisitor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Tracks metric storage, observable callbacks by instrumentation scope, and lifecycle hooks. */
public final class OtelMetricRegistry {
  public static final OtelMetricRegistry INSTANCE = new OtelMetricRegistry();

  private final Map<OtelInstrumentationScope, Map<OtelInstrumentDescriptor, OtelMetricStorage>>
      scopedStorage = new ConcurrentHashMap<>();

  private final Map<OtelInstrumentationScope, List<OtelObservable>> scopedObservables =
      new ConcurrentHashMap<>();

  private volatile Runnable shutdownHook;

  public OtelMetricStorage registerStorage(
      OtelInstrumentationScope instrumentationScope,
      OtelInstrumentDescriptor descriptor,
      Function<OtelInstrumentDescriptor, OtelMetricStorage> storageFactory) {
    return scopedStorage
        .computeIfAbsent(instrumentationScope, unused -> new ConcurrentHashMap<>())
        .computeIfAbsent(descriptor, storageFactory);
  }

  public void registerObservable(
      OtelInstrumentationScope instrumentationScope, OtelObservable observable) {
    List<OtelObservable> observables =
        scopedObservables.computeIfAbsent(instrumentationScope, unused -> new ArrayList<>());
    synchronized (observables) {
      observables.add(observable);
    }
  }

  public boolean unregisterObservable(
      OtelInstrumentationScope instrumentationScope, OtelObservable observable) {
    List<OtelObservable> observables = scopedObservables.get(instrumentationScope);
    if (observables == null) {
      return false;
    }
    synchronized (observables) {
      return observables.remove(observable);
    }
  }

  /** Registers the action to run when this registry is shut down. */
  public void registerShutdownHook(Runnable shutdownHook) {
    this.shutdownHook = shutdownHook;
  }

  /** Runs the registered shutdown hook. */
  public void shutdown() {
    Runnable shutdownHook = this.shutdownHook;
    if (shutdownHook != null) {
      shutdownHook.run();
    }
  }

  public void collectMetrics(OtlpMetricsVisitor visitor) {
    scopedStorage.forEach(
        (scope, storage) ->
            collectScopedMetrics(scope, storage, visitor.visitScopedMetrics(scope)));
  }

  private void collectScopedMetrics(
      OtelInstrumentationScope instrumentationScope,
      Map<OtelInstrumentDescriptor, OtelMetricStorage> storage,
      OtlpScopedMetricsVisitor visitor) {
    List<OtelObservable> observables = scopedObservables.get(instrumentationScope);
    if (observables != null) {
      // take local snapshot of current observables
      List<OtelObservable> observablesCopy;
      synchronized (observables) {
        observablesCopy = new ArrayList<>(observables);
      }
      // must observe measurements outside of lock
      observablesCopy.forEach(OtelObservable::observeMeasurements);
    }
    storage.forEach((descriptor, s) -> s.collectMetric(visitor.visitMetric(descriptor)));
  }
}
