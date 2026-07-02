package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.HISTOGRAM;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.LONG_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ATTRIBUTE;
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
import datadog.metrics.api.Histogram;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;
import datadog.trace.bootstrap.otlp.metrics.OtlpHistogramPoint;
import datadog.trace.core.otlp.common.OtlpGrpcSender;
import datadog.trace.core.otlp.common.OtlpHttpSender;
import datadog.trace.core.otlp.common.OtlpProtoBuffer;
import datadog.trace.core.otlp.common.OtlpSender;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link MetricWriter} that exports the existing client-side trace (RED) stats as a single
 * vendor-neutral OTLP delta-temporality histogram named {@code traces.span.sdk.metrics.duration}
 * (unit {@code s}).
 *
 * <p>This is the parallel-to-{@link SerializingMetricWriter} OTLP export path. It hangs off the
 * same in-memory aggregation ({@link ClientStatsAggregator} / {@link Aggregator}) and consumes the
 * same {@link AggregateEntry} stream; only the wire encoding and transport differ. Native msgpack
 * stats and OTLP export are mutually exclusive (selected at the factory).
 *
 * <p>Assembly mirrors {@code OtlpMetricsProtoCollector}
 */
public final class OtlpStatsMetricWriter implements MetricWriter {
  private static final Logger log = LoggerFactory.getLogger(OtlpStatsMetricWriter.class);

  static final String METRIC_NAME = "traces.span.sdk.metrics.duration";
  static final String METRIC_UNIT = "s";

  private static final OtelInstrumentDescriptor METRIC_DESCRIPTOR =
      new OtelInstrumentDescriptor(METRIC_NAME, HISTOGRAM, false, null, METRIC_UNIT);
  private static final OtelInstrumentationScope SCOPE =
      new OtelInstrumentationScope("datadog.trace.metrics", null, null);

  private static final int DP_START_TIME_FIELD = 2;
  private static final int DP_TIME_FIELD = 3;
  private static final int DP_ATTRIBUTES_FIELD = 9;

  private static final String SPAN_NAME = "span.name";
  private static final String SPAN_KIND = "span.kind";
  private static final String HTTP_REQUEST_METHOD = "http.request.method";
  private static final String HTTP_RESPONSE_STATUS_CODE = "http.response.status_code";
  private static final String HTTP_ROUTE = "http.route";
  private static final String RPC_RESPONSE_STATUS_CODE = "rpc.response.status_code";
  private static final String STATUS_CODE = "status.code";
  private static final String STATUS_CODE_ERROR = "ERROR";
  private static final String DATADOG_OPERATION_NAME = "datadog.operation.name";
  private static final String DATADOG_SPAN_TYPE = "datadog.span.type";
  private static final String DATADOG_SPAN_TOP_LEVEL = "datadog.span.top_level";
  private static final String DATADOG_ORIGIN = "datadog.origin";
  private static final String SYNTHETICS_ORIGIN = "synthetics";

  @Nullable private final OtlpSender sender;
  private final boolean otelSemanticsMode;

  // Need a temporary buffer to know what size to write for the final protobuf buffer
  private final GrowableBuffer buf = new GrowableBuffer(512);
  private final OtlpProtoBuffer protobuf = new OtlpProtoBuffer(8192);

  private long startNanos;
  private long endNanos;

  private int payloadBytes;
  private int scopedBytes;
  private int metricBytes;

  public OtlpStatsMetricWriter(Config config) {
    this(createSender(config), config.isTraceOtelSemanticsEnabled());
  }

  // visible for testing: lets tests inject a capturing sender to decode the emitted protobuf and
  // control the semantics mode
  OtlpStatsMetricWriter(@Nullable OtlpSender sender, boolean otelSemanticsMode) {
    this.sender = sender;
    this.otelSemanticsMode = otelSemanticsMode;
  }

  @Nullable
  private static OtlpSender createSender(Config config) {
    // mirrors OtlpMetricsService's protocol-based sender selection
    switch (config.getOtlpMetricsProtocol()) {
      case GRPC:
        return new OtlpGrpcSender(
            config.getOtlpMetricsEndpoint(),
            "/opentelemetry.proto.collector.metrics.v1.MetricsService/Export",
            config.getOtlpMetricsHeaders(),
            config.getOtlpMetricsTimeout(),
            config.getOtlpMetricsCompression());
      case HTTP_PROTOBUF:
        return new OtlpHttpSender(
            config.getOtlpMetricsEndpoint(),
            "/v1/metrics",
            config.getOtlpMetricsHeaders(),
            config.getOtlpMetricsTimeout(),
            config.getOtlpMetricsCompression());
      default:
        // HTTP_JSON has no protobuf-free encoder yet; JSON transport is deferred per the plan.
        log.warn(
            "OTLP trace metrics export disabled: unsupported metrics protocol {}. "
                + "Set OTEL_EXPORTER_OTLP_METRICS_PROTOCOL to grpc or http/protobuf.",
            config.getOtlpMetricsProtocol());
        return null;
    }
  }

  @Override
  public void startBucket(int metricCount, long start, long duration) {
    // start/duration arrive as epoch nanos / interval nanos (see Aggregator#report)
    this.startNanos = start;
    this.endNanos = start + duration;
    this.payloadBytes = 0;
    this.scopedBytes = 0;
    this.metricBytes = 0;
  }

  @Override
  public void add(AggregateEntry entry) {
    Histogram okLatencies = entry.getOkLatencies();
    if (!okLatencies.isEmpty()) {
      addDataPoint(entry, okLatencies, false);
    }

    Histogram errorLatencies = entry.getErrorLatencies();
    if (errorLatencies != null && !errorLatencies.isEmpty()) {
      addDataPoint(entry, errorLatencies, true);
    }
  }

  private void addDataPoint(AggregateEntry entry, Histogram latencies, boolean error) {
    writeDataPointAttributes(entry, error);
    writeTag(buf, DP_START_TIME_FIELD, I64_WIRE_TYPE);
    writeI64(buf, startNanos);
    writeTag(buf, DP_TIME_FIELD, I64_WIRE_TYPE);
    writeI64(buf, endNanos);
    long sumNanos = error ? entry.getErrorDuration() : entry.getOkDuration();
    OtlpHistogramPoint point = OtlpStatsHistogramBuckets.toHistogramPoint(latencies, sumNanos);
    metricBytes += recordDataPointMessage(buf, point, protobuf);
  }

  private void writeDataPointAttributes(AggregateEntry entry, boolean error) {
    if (error) {
      writeStringAttribute(STATUS_CODE, STATUS_CODE_ERROR);
    }
    // OTel semconv attrs are emitted in both modes
    writeStringAttribute(SPAN_NAME, entry.getResource());
    writeStringAttribute(SPAN_KIND, entry.getSpanKind());
    if (entry.hasHttpMethod()) {
      writeStringAttribute(HTTP_REQUEST_METHOD, entry.getHttpMethod());
    }
    if (entry.getHttpStatusCode() != 0) {
      writeLongAttribute(HTTP_RESPONSE_STATUS_CODE, entry.getHttpStatusCode());
    }
    if (entry.hasHttpEndpoint()) {
      writeStringAttribute(HTTP_ROUTE, entry.getHttpEndpoint());
    }
    if (entry.hasGrpcStatusCode()) {
      writeStringAttribute(RPC_RESPONSE_STATUS_CODE, entry.getGrpcStatusCode());
    }
    // Default (Datadog) mode: emit datadog.* per-point attributes
    if (!otelSemanticsMode) {
      writeStringAttribute(DATADOG_OPERATION_NAME, entry.getOperationName());
      writeStringAttribute(DATADOG_SPAN_TYPE, entry.getType());
      writeLongAttribute(
          DATADOG_SPAN_TOP_LEVEL, entry.getTopLevelCount() == entry.getHitCount() ? 1L : 0L);
      if (entry.isSynthetics()) {
        writeStringAttribute(DATADOG_ORIGIN, SYNTHETICS_ORIGIN);
      }
    }
  }

  private void writeStringAttribute(String key, @Nullable UTF8BytesString value) {
    if (value != null) {
      writeStringAttribute(key, value.toString());
    }
  }

  private void writeStringAttribute(String key, String value) {
    writeTag(buf, DP_ATTRIBUTES_FIELD, LEN_WIRE_TYPE);
    writeAttribute(buf, STRING_ATTRIBUTE, key, value);
  }

  private void writeLongAttribute(String key, long value) {
    writeTag(buf, DP_ATTRIBUTES_FIELD, LEN_WIRE_TYPE);
    writeAttribute(buf, LONG_ATTRIBUTE, key, value);
  }

  @Override
  public void finishBucket() {
    try {
      if (metricBytes > 0) {
        // trace stats histograms are inherently per-interval deltas (buckets are cleared after
        // every flush), so always encode DELTA regardless of the temporality preference
        scopedBytes += recordMetricMessage(buf, METRIC_DESCRIPTOR, metricBytes, protobuf, true);
      }
      if (scopedBytes > 0) {
        payloadBytes += recordScopedMetricsMessage(buf, SCOPE, scopedBytes, protobuf);
      }
      if (payloadBytes == 0) {
        return;
      }
      payloadBytes += protobuf.recordMessage(RESOURCE_MESSAGE);
      protobuf.recordMessage(buf, 1, payloadBytes);

      if (sender != null) {
        sender.send(protobuf.toPayload());
      }
    } finally {
      reset();
    }
  }

  @Override
  public void reset() {
    buf.reset();
    protobuf.reset();
    payloadBytes = 0;
    scopedBytes = 0;
    metricBytes = 0;
  }
}
