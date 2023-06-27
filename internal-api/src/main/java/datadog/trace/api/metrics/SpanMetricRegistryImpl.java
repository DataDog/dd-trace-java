package datadog.trace.api.metrics;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The default {@link SpanMetricRegistry} implementation. */
public class SpanMetricRegistryImpl implements SpanMetricRegistry {
  private static final SpanMetricRegistryImpl INSTANCE = new SpanMetricRegistryImpl();
  private final Map<String, SpanMetrics> spanMetrics;

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

  /**
   * Get all span metrics.
   *
   * @return All span metrics registered instances.
   */
  public Collection<SpanMetrics> getSpanMetrics() {
    return this.spanMetrics.values();
  }
}
