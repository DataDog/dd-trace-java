package datadog.trace.api.iast.telemetry;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class IastTelemetryCollectorImpl implements IastTelemetryCollector {

  /** Bounded by the size of the enum {@link IastMetric} */
  private final ConcurrentHashMap<IastMetric, IastMetricHandler> handlers =
      new ConcurrentHashMap<>(IastMetric.values().length);

  private final Function<IastMetric, IastMetricHandler> builders;

  public IastTelemetryCollectorImpl(final Function<IastMetric, IastMetricHandler> builders) {
    this.builders = builders;
  }

  @Override
  public boolean addMetric(final IastMetric metric, final long value, final String tag) {
    return getOrCreateHandler(metric).add(value, tag);
  }

  @Override
  public boolean merge(final Collection<MetricData> metrics) {
    boolean result = true;
    for (final MetricData data : metrics) {
      result &= getOrCreateHandler(data.getMetric()).merge(data);
    }
    return result;
  }

  @Override
  public Collection<MetricData> drainMetrics() {
    if (handlers.isEmpty()) {
      return Collections.emptyList();
    }
    final List<MetricData> result = new LinkedList<>();
    for (final IastMetricHandler handler : handlers.values()) {
      final Collection<MetricData> values = handler.drain();
      if (!values.isEmpty()) {
        result.addAll(values);
      }
    }
    handlers.clear();
    return result;
  }

  private IastMetricHandler getOrCreateHandler(final IastMetric metric) {
    IastMetricHandler handler = handlers.get(metric);
    if (handler == null) {
      handler = builders.apply(metric);
      final IastMetricHandler old = handlers.putIfAbsent(metric, handler);
      handler = old == null ? handler : old;
    }
    return handler;
  }
}
