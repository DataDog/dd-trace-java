package datadog.trace.core.otlp.metrics;

import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.GAUGE;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.HISTOGRAM;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.OBSERVABLE_GAUGE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.I64_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.LEN_WIRE_TYPE;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeAttribute;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeI64;
import static datadog.trace.core.otlp.common.OtlpCommonProto.writeTag;
import static datadog.trace.core.otlp.common.OtlpResourceProto.RESOURCE_MESSAGE;
import static datadog.trace.core.otlp.metrics.OtlpMetricsProto.recordDataPointMessage;
import static datadog.trace.core.otlp.metrics.OtlpMetricsProto.recordMetricMessage;
import static datadog.trace.core.otlp.metrics.OtlpMetricsProto.recordScopedMetricsMessage;

import datadog.communication.serialization.GrowableBuffer;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentType;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricRegistry;
import datadog.trace.bootstrap.otlp.metrics.OtlpDataPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricVisitor;
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricsVisitor;
import datadog.trace.bootstrap.otlp.metrics.OtlpScopedMetricsVisitor;
import datadog.trace.core.otlp.common.OtlpCommonProto;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpProtoBuffer;
import java.util.function.Consumer;

/**
 * Collects OpenTelemetry metrics and marshals them into a chunked 'metrics.proto' payload.
 *
 * <p>This collector is designed to be called by a single thread. To minimize allocations each
 * collection returns a payload only to be used by the calling thread until the next collection.
 * (The payload should be copied before passing it onto another thread.)
 *
 * <p>We use a single temporary buffer to prepare message chunks at different nesting levels. First
 * we chunk all data points for a given metric. Once the metric is complete we add the first part of
 * the metric message and its chunked data points to the scoped chunks. Once the scope is complete
 * we add the first part of the scoped metrics message and all its chunks (metric messages and data
 * points) to the payload. Once all the metrics data has been chunked we add the enclosing resource
 * metrics message to the start of the payload.
 */
public final class OtlpMetricsProtoCollector extends OtlpMetricsCollector
    implements OtlpMetricsVisitor, OtlpScopedMetricsVisitor, OtlpMetricVisitor {

  public static final OtlpMetricsProtoCollector INSTANCE =
      new OtlpMetricsProtoCollector(SystemTimeSource.INSTANCE);

  private final GrowableBuffer buf = new GrowableBuffer(512);
  private final OtlpProtoBuffer protobuf = new OtlpProtoBuffer(8192);

  private final TimeSource timeSource;

  private long startNanos;
  private long endNanos;

  // total number of chunked bytes at different nesting levels
  private int payloadBytes;
  private int scopedBytes;
  private int metricBytes;

  private OtelInstrumentationScope currentScope;
  private OtelInstrumentDescriptor currentMetric;

  public OtlpMetricsProtoCollector(TimeSource timeSource) {
    this.timeSource = timeSource;
    this.endNanos = timeSource.getCurrentTimeNanos();
  }

  /**
   * Collects OpenTelemetry metrics and marshals them into a chunked payload.
   *
   * <p>This payload is only valid for the calling thread until the next collection.
   */
  @Override
  public OtlpPayload collectMetrics() {
    return collectMetrics(OtelMetricRegistry.INSTANCE::collectMetrics);
  }

  OtlpPayload collectMetrics(Consumer<OtlpMetricsVisitor> registry) {
    start();
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

    // remove stale entries from caches
    OtlpCommonProto.recalibrateCaches();
  }

  /** Cleanup elements used to collect metrics data. */
  private void stop() {
    buf.reset();
    protobuf.reset();

    payloadBytes = 0;
    scopedBytes = 0;
    metricBytes = 0;

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
    // add attribute to the data point currently being collected
    writeTag(buf, currentMetric.getType() == HISTOGRAM ? 9 : 7, LEN_WIRE_TYPE);
    writeAttribute(buf, type, key, value);
  }

  @Override
  public void visitDataPoint(OtlpDataPoint point) {
    OtelInstrumentType metricType = currentMetric.getType();

    // gauges don't have a start time (no aggregation temporality)
    if (metricType != GAUGE && metricType != OBSERVABLE_GAUGE) {
      writeTag(buf, 2, I64_WIRE_TYPE);
      writeI64(buf, startNanos);
    }
    writeTag(buf, 3, I64_WIRE_TYPE);
    writeI64(buf, endNanos);

    // add complete data point message to the metric chunks
    metricBytes += recordDataPointMessage(buf, point, protobuf);
  }

  // called once we've processed all scopes and metric messages
  private OtlpPayload completePayload() {
    if (currentScope != null) {
      completeScope();
    }

    if (payloadBytes == 0) {
      return OtlpPayload.EMPTY;
    }

    // prepend the canned resource chunk
    payloadBytes += protobuf.recordMessage(RESOURCE_MESSAGE);

    // finally prepend the total length of all collected chunks
    protobuf.recordMessage(buf, 1, payloadBytes);
    return protobuf.toPayload();
  }

  // called once we've processed all metrics in a specific scope
  private void completeScope() {
    if (currentMetric != null) {
      completeMetric();
    }

    // add scoped metrics message prefix to its nested chunks and promote to payload
    if (scopedBytes > 0) {
      payloadBytes += recordScopedMetricsMessage(buf, currentScope, scopedBytes, protobuf);
    }

    // reset temporary elements for next scope
    currentScope = null;
    scopedBytes = 0;
  }

  // called once we've processed all data points in a specific metric
  private void completeMetric() {

    // add metric message prefix to its nested chunks and promote to scoped
    if (metricBytes > 0) {
      scopedBytes += recordMetricMessage(buf, currentMetric, metricBytes, protobuf);
    }

    // reset temporary elements for next metric
    currentMetric = null;
    metricBytes = 0;
  }
}
