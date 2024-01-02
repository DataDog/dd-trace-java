package datadog.trace.api.metrics;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The default {@link SpanMetricRegistry} implementation. */
public class SpanMetricRegistryImpl implements SpanMetricRegistry {
  private static final SpanMetricRegistryImpl INSTANCE = new SpanMetricRegistryImpl();
  private final Map<String, SpanMetricsImpl> spanMetrics;

  public static SpanMetricRegistryImpl getInstance() {
    return INSTANCE;
  }

  private SpanMetricRegistryImpl() {
    this.spanMetrics = new ConcurrentHashMap<>();
  }

  @Override
  public SpanMetrics get(String instrumentationName) {
    return this.spanMetrics.computeIfAbsent(instrumentationName, SpanMetricsImpl::new);
  }

  @Override
  public String summary() {
    StringBuilder summary = new StringBuilder();
    for (SpanMetricsImpl metric : spanMetrics.values()) {
      summary.append(metric.getInstrumentationName());
      String separator = ": ";
      for (CoreCounter counter : metric.getCounters()) {
        summary.append(separator).append(counter.getName()).append('=').append(counter.getValue());
        separator = ", ";
      }
      summary.append('\n');
    }
    return summary.toString();
  }

  /**
   * Get all span metrics.
   *
   * @return All span metrics registered instances.
   */
  public Collection<SpanMetricsImpl> getSpanMetrics() {
    return this.spanMetrics.values();
  }
}
