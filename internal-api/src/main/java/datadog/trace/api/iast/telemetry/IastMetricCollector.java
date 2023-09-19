package datadog.trace.api.iast.telemetry;

import static datadog.trace.api.iast.telemetry.IastMetric.Scope.REQUEST;

import datadog.trace.api.Config;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.telemetry.IastMetric.Tag;
import datadog.trace.api.telemetry.MetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLongArray;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("resource")
public class IastMetricCollector implements MetricCollector<IastMetricCollector.IastMetricData> {

  private static final Logger LOGGER = LoggerFactory.getLogger(IastMetricCollector.class);

  private static final String NAMESPACE = "iast";

  private static final Verbosity VERBOSITY = Config.get().getIastTelemetryVerbosity();

  private static final IastMetricCollector INSTANCE =
      VERBOSITY != Verbosity.OFF ? new IastMetricCollector() : new NoOpInstance();

  public static IastMetricCollector get() {
    return INSTANCE;
  }

  private static IastMetricCollector get(@Nullable Object ctx) {
    if (VERBOSITY == Verbosity.OFF) {
      return INSTANCE;
    }
    if (ctx == null) {
      ctx = activeRequestContext();
    }
    if (ctx instanceof RequestContext) {
      ctx = ((RequestContext) ctx).getData(RequestContextSlot.IAST);
    }
    if (ctx instanceof HasMetricCollector) {
      final IastMetricCollector collector = ((HasMetricCollector) ctx).getMetricCollector();
      if (collector != null) {
        return collector;
      }
    }
    // if no active request then forward to the global collector
    return INSTANCE;
  }

  private final BlockingQueue<IastMetricData> rawMetricsQueue;

  private final AtomicLongArray counters;

  public IastMetricCollector() {
    this(new ArrayBlockingQueue<>(RAW_QUEUE_SIZE), new AtomicLongArray(IastMetric.count()));
  }

  protected IastMetricCollector(
      final BlockingQueue<IastMetricData> rawMetricsQueue, final AtomicLongArray counters) {
    this.rawMetricsQueue = rawMetricsQueue;
    this.counters = counters;
  }

  /** Prefer using {@link #add(IastMetric, int, Object)} if possible */
  public static void add(@Nonnull final IastMetric metric, final int value) {
    add(metric, value, null);
  }

  public static void add(
      @Nonnull final IastMetric metric, final int value, @Nullable final Object ctx) {
    add(metric, (byte) -1, value, null);
  }

  /** Prefer using {@link #add(IastMetric, byte, int, Object)} if possible */
  public static void add(@Nonnull final IastMetric metric, final byte tagValue, final int value) {
    add(metric, tagValue, value, null);
  }

  public static void add(
      @Nonnull final IastMetric metric,
      final byte tagValue,
      final int value,
      @Nullable final Object ctx) {
    try {
      final IastMetricCollector instance = metric.getScope() == REQUEST ? get(ctx) : INSTANCE;
      instance.addMetric(metric, tagValue, value);
    } catch (final Throwable e) {
      LOGGER.warn("Failed to add metric {} with tag {}", metric, tagValue, e);
    }
  }

  public void addMetric(final IastMetric metric, final byte tagValue, final int value) {
    final Tag tag = metric.getTag();
    if (tag != null && tag.isWrapped(tagValue)) {
      // e.g.: VulnerabilityTypes.RESPONSE_HEADER
      for (final byte unwrapped : metric.getTag().unwrap(tagValue)) {
        increment(metric.getIndex(unwrapped), value);
      }
    } else {
      increment(metric.getIndex(tagValue), value);
    }
  }

  private void increment(final int index, final int value) {
    if (index >= 0) {
      counters.getAndAdd(index, value);
    }
  }

  public void merge(final Collection<IastMetricData> metrics) {
    for (final IastMetricData data : metrics) {
      final IastMetric metric = data.metric;
      final byte tagValue = data.tagValue;
      final long value = data.value.longValue();
      counters.getAndAdd(metric.getIndex(tagValue), value);
    }
  }

  @Override
  public void prepareMetrics() {
    for (final IastMetric metric : IastMetric.values()) {
      if (metric.getTag() == null) {
        prepareMetric(metric, (byte) -1);
      } else {
        for (final byte tagValue : metric.getTag().getValues()) {
          prepareMetric(metric, tagValue);
        }
      }
    }
  }

  private void prepareMetric(final IastMetric metric, final byte tagValue) {
    final int index = metric.getIndex(tagValue);
    final long value = counters.getAndSet(index, 0);
    if (value > 0) {
      rawMetricsQueue.offer(new IastMetricData(metric, tagValue, value));
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

    private final IastMetric metric;
    private final byte tagValue;

    public IastMetricData(final IastMetric metric, final byte tagValue, final long value) {
      super(
          NAMESPACE,
          metric.isCommon(),
          metric.getName(),
          "count",
          value,
          computeTag(metric, tagValue));
      this.metric = metric;
      this.tagValue = tagValue;
    }

    public IastMetric getMetric() {
      return metric;
    }

    public byte getTagValue() {
      return tagValue;
    }

    public String getSpanTag() {
      if (metric.getTag() == null) {
        return metric.getName();
      }
      final String tag = metric.getTag().toString(tagValue);
      final String spanTag = tag.toLowerCase(Locale.ROOT).replace('.', '_');
      return String.format("%s.%s", metric.getName(), spanTag);
    }

    public static String computeTag(final IastMetric metric, final byte tagValue) {
      if (metric.getTag() == null) {
        return null;
      }
      return String.format("%s:%s", metric.getTag().getName(), metric.getTag().toString(tagValue));
    }
  }

  private static RequestContext activeRequestContext() {
    final AgentSpan span = AgentTracer.activeSpan();
    return span == null ? null : span.getRequestContext();
  }

  private static class NoOpInstance extends IastMetricCollector {

    public NoOpInstance() {
      super(null, null);
    }

    @Override
    public void addMetric(final IastMetric metric, final byte tagValue, final int value) {}

    @Override
    public void merge(final Collection<IastMetricData> metrics) {}

    @Override
    public void prepareMetrics() {}

    @Override
    public Collection<IastMetricData> drain() {
      return Collections.emptyList();
    }
  }

  public interface HasMetricCollector {

    IastMetricCollector getMetricCollector();
  }
}
