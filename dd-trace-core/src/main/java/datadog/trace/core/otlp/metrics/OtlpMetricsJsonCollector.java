package datadog.trace.core.otlp.metrics;

import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.GAUGE;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.OBSERVABLE_GAUGE;
import static datadog.trace.core.otlp.common.OtlpCommonJson.writeAttribute;
import static datadog.trace.core.otlp.common.OtlpCommonJson.writeScopeAndSchema;
import static datadog.trace.core.otlp.common.OtlpPayload.JSON_CONTENT_TYPE;
import static datadog.trace.core.otlp.common.OtlpResourceJson.RESOURCE_FRAGMENT;
import static datadog.trace.core.otlp.metrics.OtlpMetricsJson.closeMetric;
import static datadog.trace.core.otlp.metrics.OtlpMetricsJson.openMetric;
import static datadog.trace.core.otlp.metrics.OtlpMetricsJson.writeDataPointValue;

import datadog.json.JsonWriter;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentType;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricRegistry;
import datadog.trace.bootstrap.otlp.metrics.OtlpDataPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricVisitor;
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricsVisitor;
import datadog.trace.bootstrap.otlp.metrics.OtlpScopedMetricsVisitor;
import datadog.trace.core.otlp.common.LazyJsonArray;
import datadog.trace.core.otlp.common.OtlpPayload;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Collects OpenTelemetry metrics and marshals them into a 'metrics.proto' JSON payload.
 *
 * <p>This collector is designed to be called by a single thread. To minimize allocations each
 * collection returns a payload only to be used by the calling thread until the next collection.
 * (The payload should be copied before passing it onto another thread.)
 *
 * <p>Unlike the protobuf collector, attributes for a data point are written directly into the
 * currently open data point object as {@code visitAttribute} is called. A scope's header (and its
 * {@code metrics} array) and a metric's header (and its {@code gauge}/{@code sum}/{@code histogram}
 * wrapper) are only opened lazily, on the first attribute or data point visited for them, so scopes
 * and metrics with no data points contribute nothing to the payload — matching the protobuf
 * collector's behavior.
 */
public final class OtlpMetricsJsonCollector extends OtlpMetricsCollector
    implements OtlpMetricsVisitor, OtlpScopedMetricsVisitor, OtlpMetricVisitor {

  private final TimeSource timeSource;

  private final boolean forceHistogramDelta;

  // resource fragment prepended to every payload; lets callers pick the plain vendor-neutral
  // resource or the datadog-attrs variant (datadog.runtime_id / process tags)
  private final String resourceFragment;

  private long startNanos;
  private long endNanos;
  private String startNanosStr;
  private String endNanosStr;

  private JsonWriter writer;
  private boolean anyDataPointWritten;
  private boolean scopeStarted;
  private boolean metricStarted;
  private boolean dataPointStarted;

  private final LazyJsonArray attributesArray = new LazyJsonArray();

  private OtelInstrumentationScope currentScope;
  private OtelInstrumentDescriptor currentMetric;

  public OtlpMetricsJsonCollector(TimeSource timeSource) {
    this(timeSource, false);
  }

  OtlpMetricsJsonCollector(TimeSource timeSource, boolean forceHistogramDelta) {
    this(timeSource, forceHistogramDelta, RESOURCE_FRAGMENT);
  }

  OtlpMetricsJsonCollector(
      TimeSource timeSource, boolean forceHistogramDelta, String resourceFragment) {
    this.timeSource = timeSource;
    this.endNanos = timeSource.getCurrentTimeNanos();
    this.forceHistogramDelta = forceHistogramDelta;
    this.resourceFragment = resourceFragment;
  }

  /**
   * Collects OpenTelemetry metrics and marshals them into a JSON payload.
   *
   * <p>This payload is only valid for the calling thread until the next collection.
   */
  @Override
  public OtlpPayload collectMetrics() {
    return collectMetrics(OtelMetricRegistry.INSTANCE::collectMetrics);
  }

  OtlpPayload collectMetrics(Consumer<OtlpMetricsVisitor> registry) {
    start();
    return run(registry);
  }

  @Override
  OtlpPayload collectMetrics(
      Consumer<OtlpMetricsVisitor> registry, long startNanos, long endNanos) {
    startWithWindow(startNanos, endNanos);
    return run(registry);
  }

  private OtlpPayload run(Consumer<OtlpMetricsVisitor> registry) {
    try {
      registry.accept(this);
      return completePayload();
    } finally {
      stop();
    }
  }

  /** Prepare temporary elements to collect metrics data. */
  private void start() {
    // shift interval to cover last collection to now
    startNanos = endNanos;
    endNanos = timeSource.getCurrentTimeNanos();
    beginPayload();
  }

  private void startWithWindow(long startNanos, long endNanos) {
    this.startNanos = startNanos;
    this.endNanos = endNanos;
    beginPayload();
  }

  private void beginPayload() {
    startNanosStr = Long.toString(startNanos);
    endNanosStr = Long.toString(endNanos);

    writer = new JsonWriter();
    writer.beginObject();
    writer.name("resourceMetrics").beginArray();
    writer.beginObject();
    writer.name("resource").jsonValue(resourceFragment);
    writer.name("scopeMetrics").beginArray();
  }

  /** Cleanup elements used to collect metrics data. */
  private void stop() {
    attributesArray.reset();

    anyDataPointWritten = false;

    writer = null;

    scopeStarted = false;
    metricStarted = false;
    dataPointStarted = false;

    currentScope = null;
    currentMetric = null;
  }

  @Override
  public OtlpScopedMetricsVisitor visitScopedMetrics(OtelInstrumentationScope scope) {
    if (currentScope != null) {
      completeScope();
    }
    currentScope = scope;
    return this;
  }

  @Override
  public OtlpMetricVisitor visitMetric(OtelInstrumentDescriptor metric) {
    if (currentMetric != null) {
      completeMetric();
    }
    currentMetric = metric;
    return this;
  }

  @Override
  public void visitAttribute(int type, String key, Object value) {
    ensureMetricStarted();
    ensureDataPointStarted();
    attributesArray.ensureOpen(writer, "attributes");
    writeAttribute(writer, type, key, value);
  }

  @Override
  public void visitDataPoint(OtlpDataPoint point) {
    ensureMetricStarted();
    ensureDataPointStarted();
    attributesArray.closeIfOpen(writer);

    OtelInstrumentType metricType = currentMetric.getType();

    // gauges don't have a start time (no aggregation temporality)
    if (metricType != GAUGE && metricType != OBSERVABLE_GAUGE) {
      writer.name("startTimeUnixNano").value(startNanosStr);
    }
    writer.name("timeUnixNano").value(endNanosStr);
    writeDataPointValue(writer, point);

    writer.endObject(); // data point

    dataPointStarted = false;
    anyDataPointWritten = true;
  }

  // opens the scope header (and its metrics array) on first use
  private void ensureScopeStarted() {
    if (!scopeStarted) {
      writer.beginObject();
      writeScopeAndSchema(writer, currentScope);
      writer.name("metrics").beginArray();
      scopeStarted = true;
    }
  }

  // opens the metric header (and its gauge/sum/histogram wrapper) on first use
  private void ensureMetricStarted() {
    if (!metricStarted) {
      ensureScopeStarted();
      openMetric(writer, currentMetric, forceHistogramDelta);
      metricStarted = true;
    }
  }

  // opens the data point object on first attribute or value written for it
  private void ensureDataPointStarted() {
    if (!dataPointStarted) {
      writer.beginObject();
      dataPointStarted = true;
    }
  }

  // called once we've processed all scopes and metric messages
  private OtlpPayload completePayload() {
    if (currentScope != null) {
      completeScope();
    }

    writer.endArray(); // scopeMetrics
    writer.endObject(); // resourceMetrics[0]
    writer.endArray(); // resourceMetrics
    writer.endObject(); // root

    if (!anyDataPointWritten) {
      return OtlpPayload.EMPTY;
    }

    byte[] bytes = writer.toByteArray();
    return new OtlpPayload(ByteBuffer.wrap(bytes), JSON_CONTENT_TYPE);
  }

  // called once we've processed all metrics in a specific scope
  private void completeScope() {
    if (currentMetric != null) {
      completeMetric();
    }

    if (scopeStarted) {
      writer.endArray(); // metrics
      writer.endObject(); // scopeMetrics[0]
      scopeStarted = false;
    }

    // reset temporary elements for next scope
    currentScope = null;
  }

  // called once we've processed all data points in a specific metric
  private void completeMetric() {
    if (metricStarted) {
      closeMetric(writer);
      metricStarted = false;
    }

    // reset temporary elements for next metric
    currentMetric = null;
  }
}
