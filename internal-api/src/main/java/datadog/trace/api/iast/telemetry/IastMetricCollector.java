package datadog.trace.api.iast.telemetry;

import static datadog.trace.api.iast.telemetry.IastMetric.Scope.REQUEST;

import datadog.trace.api.Config;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.telemetry.MetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLongArray;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IastMetricCollector implements MetricCollector<IastMetricCollector.IastMetricData> {

  private static final Logger LOGGER = LoggerFactory.getLogger(IastMetricCollector.class);

  private static final IastMetricCollector INSTANCE =
      isEnabled() ? new IastMetricCollector() : new NoOpInstance();

  private static final String NAMESPACE = "iast";

  public static IastMetricCollector get() {
    return INSTANCE;
  }

  public static IastMetricCollector get(
      @Nonnull final IastMetric metric, @Nullable RequestContext ctx) {
    if (metric.getScope() == REQUEST) {
      ctx = activeRequestContext(ctx);
      if (ctx != null) {
        final Object iastCtx = ctx.getData(RequestContextSlot.IAST);
        if (iastCtx instanceof HasTelemetryCollector) {
          return ((HasTelemetryCollector) iastCtx).getTelemetryCollector();
        }
      }
    }
    // if no active request then forward to the global collector
    return get();
  }

  private final BlockingQueue<IastMetricData> rawMetricsQueue;

  private final AtomicLongArray counters;

  public IastMetricCollector() {
    this(new ArrayBlockingQueue<>(RAW_QUEUE_SIZE), new AtomicLongArray(IastMetric.values().length));
  }

  protected IastMetricCollector(
      final BlockingQueue<IastMetricData> rawMetricsQueue, final AtomicLongArray counters) {
    this.rawMetricsQueue = rawMetricsQueue;
    this.counters = counters;
  }

  /** Prefer using {@link #add(IastMetric, long, RequestContext)} if possible */
  public static void add(@Nonnull final IastMetric metric, final long value) {
    add(metric, value, null);
  }

  public static void add(
      @Nonnull final IastMetric metric, final long value, @Nullable final RequestContext ctx) {
    try {
      final IastMetricCollector instance = get(metric, ctx);
      instance.addMetric(metric, value);
    } catch (final Throwable e) {
      LOGGER.warn("Failed to add metric {}", metric, e);
    }
  }

  public void addMetric(final IastMetric metric, final long value) {
    counters.addAndGet(metric.ordinal(), value);
  }

  public void merge(final Collection<IastMetricData> metrics) {
    for (final IastMetricData data : metrics) {
      final IastMetric metric = data.metric;
      final long value = data.counter;
      counters.addAndGet(metric.ordinal(), value);
    }
  }

  @Override
  public void prepareMetrics() {
    for (final IastMetric metric : IastMetric.values()) {
      final long value = counters.getAndSet(metric.ordinal(), 0);
      if (value > 0) {
        rawMetricsQueue.offer(new IastMetricData(metric, value));
      }
    }
  }

  @Override
  public Collection<IastMetricData> drain() {
    if (!rawMetricsQueue.isEmpty()) {
      final List<IastMetricData> list = new LinkedList<>();
      final int drained = rawMetricsQueue.drainTo(list);
      if (drained > 0) {
        return list;
      }
    }
    return Collections.emptyList();
  }

  public static class IastMetricData extends MetricCollector.Metric {

    public final IastMetric metric;

    public IastMetricData(final IastMetric metric, final long value) {
      super(NAMESPACE, metric.isCommon(), metric.getName(), value, metric.getTag());
      this.metric = metric;
    }
  }

  private static RequestContext activeRequestContext(final RequestContext context) {
    if (context != null) {
      return context;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    return span == null ? null : span.getRequestContext();
  }

  private static class NoOpInstance extends IastMetricCollector {

    public NoOpInstance() {
      super(null, null);
    }

    @Override
    public void addMetric(final IastMetric metric, final long value) {}

    @Override
    public void merge(final Collection<IastMetricData> metrics) {}

    @Override
    public void prepareMetrics() {}

    @Override
    public Collection<IastMetricData> drain() {
      return Collections.emptyList();
    }
  }

  private static boolean isEnabled() {
    final Config config = Config.get();
    if (!config.isTelemetryEnabled() || config.getIastTelemetryVerbosity() == Verbosity.OFF) {
      return false;
    }
    return true;
  }

  public interface HasTelemetryCollector {
    IastMetricCollector getTelemetryCollector();
  }
}
