package datadog.trace.api.iast.telemetry;

import static datadog.trace.api.iast.telemetry.IastMetric.Scope.REQUEST;
import static datadog.trace.api.iast.telemetry.IastMetricHandler.aggregated;
import static datadog.trace.api.iast.telemetry.IastMetricHandler.conflated;

import datadog.trace.api.Config;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface IastTelemetryCollector {

  Logger LOGGER = LoggerFactory.getLogger(IastTelemetryCollector.class);

  class Holder {
    public static final IastTelemetryCollector GLOBAL = globalCollector();

    public static IastTelemetryCollector get(
        @Nonnull final IastMetric metric, @Nullable RequestContext ctx) {
      if (metric.getScope() == REQUEST) {
        ctx = activeRequestContext(ctx);
        if (ctx != null) {
          final Object iastRequestContext = ctx.getData(RequestContextSlot.IAST);
          if (iastRequestContext instanceof HasTelemetryCollector) {
            return ((HasTelemetryCollector) iastRequestContext).getTelemetryCollector();
          }
        }
      }
      // if no active request then forward to the global collector
      return GLOBAL;
    }

    private static RequestContext activeRequestContext(final RequestContext context) {
      if (context != null) {
        return context;
      }
      final AgentSpan span = AgentTracer.activeSpan();
      return span == null ? null : span.getRequestContext();
    }

    // visible for testing
    static IastTelemetryCollector globalCollector() {
      final Config config = Config.get();
      if (!config.isTelemetryEnabled() || config.getIastTelemetryVerbosity() == Verbosity.OFF) {
        return new NoOpTelemetryCollector();
      }
      return new IastTelemetryCollectorImpl(Holder::globalHandlerFor);
    }

    /** One point per trace for request scoped metrics and single point for global metrics */
    private static IastMetricHandler globalHandlerFor(final IastMetric metric) {
      switch (metric) {
        case TAINTED_FLAT_MODE:
        case REQUEST_TAINTED:
          return aggregated(metric);
        default:
          return conflated(metric);
      }
    }
  }

  /** Prefer using {@link #add(IastMetric, long, RequestContext)} if possible */
  static void add(@Nonnull final IastMetric metric, final long value) {
    add(metric, value, null, null);
  }

  /** Prefer using {@link #add(IastMetric, long, String, RequestContext)} if possible */
  static void add(@Nonnull final IastMetric metric, final long value, @Nullable final String tag) {
    add(metric, value, tag, null);
  }

  static void add(
      @Nonnull final IastMetric metric, final long value, @Nullable final RequestContext ctx) {
    add(metric, value, null, ctx);
  }

  static void add(
      @Nonnull final IastMetric metric,
      final long value,
      @Nullable final String tag,
      @Nullable final RequestContext ctx) {
    try {
      final IastTelemetryCollector instance = Holder.get(metric, ctx);
      final boolean added = instance.addMetric(metric, value, tag);
      if (!added) {
        LOGGER.debug("Failed to add metric {}", metric);
      }
    } catch (final Throwable e) {
      LOGGER.warn("Failed to add metric {}", metric, e);
    }
  }

  static Collection<MetricData> drain() {
    return Holder.GLOBAL.drainMetrics();
  }

  boolean addMetric(final IastMetric metric, final long value, final String tag);

  boolean merge(final Collection<MetricData> metrics);

  Collection<MetricData> drainMetrics();

  interface HasTelemetryCollector {
    IastTelemetryCollector getTelemetryCollector();
  }

  final class MetricData {
    private final IastMetric metric;
    private final String tag;
    private final List<Point> points;

    public MetricData(final IastMetric metric, final List<Point> points) {
      this(metric, null, points);
    }

    public MetricData(final IastMetric metric, final String tag, final List<Point> points) {
      this.metric = metric;
      this.tag = tag;
      this.points = points;
    }

    public IastMetric getMetric() {
      return metric;
    }

    public String getTag() {
      return tag;
    }

    public List<Point> getPoints() {
      return points;
    }
  }

  final class Point {
    private final long timestamp;
    private final long value;

    public Point(final long value) {
      this(System.currentTimeMillis() / 1000, value);
    }

    public Point(final long timestamp, final long value) {
      this.timestamp = timestamp;
      this.value = value;
    }

    public long getTimestamp() {
      return timestamp;
    }

    public long getValue() {
      return value;
    }
  }
}
