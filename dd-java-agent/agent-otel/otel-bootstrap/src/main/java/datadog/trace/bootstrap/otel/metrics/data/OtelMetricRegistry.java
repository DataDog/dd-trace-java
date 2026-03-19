package datadog.trace.bootstrap.otel.metrics.data;

import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;
import datadog.trace.bootstrap.otel.metrics.export.OtelMetricsVisitor;
import datadog.trace.bootstrap.otel.metrics.export.OtelScopedMetricsVisitor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Tracks metric storage and observable callbacks by instrumentation scope. */
public final class OtelMetricRegistry {
  public static final OtelMetricRegistry INSTANCE = new OtelMetricRegistry();

  private final Map<OtelInstrumentationScope, Map<OtelInstrumentDescriptor, OtelMetricStorage>>
      scopedStorage = new ConcurrentHashMap<>();

  private final Map<OtelInstrumentationScope, List<OtelObservable>> scopedObservables =
      new ConcurrentHashMap<>();

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

  public void collectMetrics(OtelMetricsVisitor visitor) {
    scopedStorage.forEach(
        (scope, storage) ->
            collectScopedMetrics(scope, storage, visitor.visitScopedMetrics(scope)));
  }

  private void collectScopedMetrics(
      OtelInstrumentationScope instrumentationScope,
      Map<OtelInstrumentDescriptor, OtelMetricStorage> storage,
      OtelScopedMetricsVisitor visitor) {
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
