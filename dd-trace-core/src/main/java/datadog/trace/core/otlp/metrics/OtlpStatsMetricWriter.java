package datadog.trace.core.otlp.metrics;

import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.HISTOGRAM;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.BOOLEAN_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.LONG_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ARRAY_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ATTRIBUTE;

import datadog.metrics.api.Histogram;
import datadog.trace.api.Config;
import datadog.trace.api.config.OtlpConfig;
import datadog.trace.api.telemetry.OtlpTelemetry;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;
import datadog.trace.bootstrap.otlp.metrics.OtlpDataPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricVisitor;
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricsVisitor;
import datadog.trace.common.metrics.AggregateEntry;
import datadog.trace.common.metrics.MetricWriter;
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpResourceJson;
import datadog.trace.core.otlp.common.OtlpResourceProto;
import datadog.trace.core.otlp.common.OtlpSender;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A {@link MetricWriter} that exports the client-side trace metrics as a delta-temporality OTLP
 * histogram ({@code traces.span.sdk.metrics.duration}, unit {@code s}), the OTLP-native alternative
 * to {@code SerializingMetricWriter}'s msgpack. It transforms each {@link AggregateEntry} into an
 * OTLP histogram data point and pushes it through the shared {@link OtlpMetricsProtoCollector}.
 */
public final class OtlpStatsMetricWriter implements MetricWriter {
  static final String METRIC_NAME = "traces.span.sdk.metrics.duration";
  static final String METRIC_UNIT = "s";

  private static final OtelInstrumentDescriptor METRIC_DESCRIPTOR =
      new OtelInstrumentDescriptor(METRIC_NAME, HISTOGRAM, false, null, METRIC_UNIT);
  private static final OtelInstrumentationScope SCOPE =
      new OtelInstrumentationScope("datadog.trace.metrics", null, null);

  private static final String SERVICE_NAME = "service.name";
  private static final String SPAN_NAME = "span.name";
  private static final String SPAN_KIND = "span.kind";
  private static final String HTTP_REQUEST_METHOD = "http.request.method";
  private static final String HTTP_RESPONSE_STATUS_CODE = "http.response.status_code";
  private static final String HTTP_ROUTE = "http.route";
  private static final String RPC_RESPONSE_STATUS_CODE = "rpc.response.status_code";
  private static final String STATUS_CODE = "status.code";
  private static final String STATUS_CODE_OK = "STATUS_CODE_OK";
  private static final String STATUS_CODE_ERROR = "STATUS_CODE_ERROR";
  private static final String DATADOG_OPERATION_NAME = "datadog.operation.name";
  private static final String DATADOG_SPAN_TYPE = "datadog.span.type";
  private static final String DATADOG_SPAN_TOP_LEVEL = "datadog.span.top_level";
  private static final String DATADOG_IS_TRACE_ROOT = "datadog.is_trace_root";
  private static final String DATADOG_ORIGIN = "datadog.origin";
  private static final String DATADOG_PEER_TAGS = "datadog.peer_tags";
  private static final String SYNTHETICS_ORIGIN = "synthetics";

  private static final String SPAN_KIND_SERVER = "SPAN_KIND_SERVER";
  private static final String SPAN_KIND_CLIENT = "SPAN_KIND_CLIENT";
  private static final String SPAN_KIND_PRODUCER = "SPAN_KIND_PRODUCER";
  private static final String SPAN_KIND_CONSUMER = "SPAN_KIND_CONSUMER";
  private static final String SPAN_KIND_INTERNAL = "SPAN_KIND_INTERNAL";

  @Nullable private final OtlpSender sender;
  private final boolean otelSemanticsMode;

  // own single-thread collector; forced to DELTA since trace-stats buckets are per-interval deltas.
  private final OtlpMetricsCollector collector;

  // data points snapshotted during add(), replayed through the visitor in finishBucket()
  private final List<PendingPoint> pending = new ArrayList<>();

  private long startNanos;
  private long endNanos;

  // Represent a single datapoint from Aggregator.add
  private static final class PendingPoint {
    final AggregateEntry entry;
    final OtlpDataPoint point;
    final boolean error;
    final boolean allTopLevel;

    PendingPoint(AggregateEntry entry, OtlpDataPoint point, boolean error, boolean allTopLevel) {
      this.entry = entry;
      this.point = point;
      this.error = error;
      this.allTopLevel = allTopLevel;
    }
  }

  public OtlpStatsMetricWriter(Config config) {
    // shared protocol-based sender selection so both OTLP metrics export paths agree
    this(
        OtlpMetricsSenderFactory.create(config),
        config.getOtlpMetricsProtocol(),
        config.isTraceOtelSemanticsEnabled());
  }

  // visible for testing: lets tests inject a capturing sender to decode the emitted payload and
  // control the semantics mode
  OtlpStatsMetricWriter(@Nullable OtlpSender sender, boolean otelSemanticsMode) {
    this(sender, OtlpConfig.Protocol.HTTP_PROTOBUF, otelSemanticsMode);
  }

  private OtlpStatsMetricWriter(
      @Nullable OtlpSender sender, OtlpConfig.Protocol protocol, boolean otelSemanticsMode) {
    this.sender = sender;
    this.otelSemanticsMode = otelSemanticsMode;
    // Default mode carries datadog.runtime_id / process tags on the Resource; OTel-semantics mode
    // uses the plain vendor-neutral resource (no datadog.*).
    this.collector =
        protocol == OtlpConfig.Protocol.HTTP_JSON
            ? new OtlpMetricsJsonCollector(
                SystemTimeSource.INSTANCE,
                true,
                otelSemanticsMode
                    ? OtlpResourceJson.RESOURCE_FRAGMENT
                    : OtlpResourceJson.RESOURCE_FRAGMENT_WITH_DATADOG_ATTRS)
            : new OtlpMetricsProtoCollector(
                SystemTimeSource.INSTANCE,
                true,
                otelSemanticsMode
                    ? OtlpResourceProto.RESOURCE_MESSAGE
                    : OtlpResourceProto.RESOURCE_MESSAGE_WITH_DATADOG_ATTRS);
  }

  @Override
  public void startBucket(int metricCount, long start, long duration) {
    // start/duration arrive as epoch nanos / interval nanos (see Aggregator#report)
    this.startNanos = start;
    this.endNanos = start + duration;
    pending.clear();
  }

  @Override
  public void add(AggregateEntry entry) {
    // Value gets wiped when Aggregator clears the entry. Need to save it here
    boolean allTopLevel = entry.getTopLevelCount() == entry.getHitCount();

    Histogram okLatencies = entry.getOkLatencies();
    if (!okLatencies.isEmpty()) {
      pending.add(
          new PendingPoint(
              entry,
              OtlpStatsHistogramBuckets.toHistogramPoint(okLatencies, entry.getOkDuration()),
              false,
              allTopLevel));
    }

    Histogram errorLatencies = entry.getErrorLatencies();
    if (errorLatencies != null && !errorLatencies.isEmpty()) {
      pending.add(
          new PendingPoint(
              entry,
              OtlpStatsHistogramBuckets.toHistogramPoint(errorLatencies, entry.getErrorDuration()),
              true,
              allTopLevel));
    }
  }

  @Override
  public void finishBucket() {
    try {
      if (pending.isEmpty() || sender == null) {
        return;
      }
      OtlpPayload payload = collector.collectMetrics(this::emit, startNanos, endNanos);
      if (payload != OtlpPayload.EMPTY) {
        OtlpTelemetry.getInstance().onMetricsExportAttempt();
        RemoteApi.Response response = sender.send(payload);
        OtlpTelemetry.getInstance().onMetricsExportComplete(response.success());
      }
    } finally {
      pending.clear();
    }
  }

  @Override
  public void reset() {
    pending.clear();
  }

  public void shutdown() {
    if (sender != null) {
      sender.shutdown();
    }
  }

  /**
   * Pushes the buffered entries through the metric visitor: one OTLP histogram data point per
   * non-empty ok/error latency series. Called by {@link OtlpMetricsProtoCollector#collectMetrics}
   * with the collector itself as the visitor.
   */
  private void emit(OtlpMetricsVisitor visitor) {
    OtlpMetricVisitor metric = visitor.visitScopedMetrics(SCOPE).visitMetric(METRIC_DESCRIPTOR);
    for (PendingPoint p : pending) {
      // attributes must precede the data point (OtlpMetricVisitor contract)
      emitDataPointAttributes(metric, p.entry, p.error, p.allTopLevel);
      metric.visitDataPoint(p.point);
    }
  }

  private void emitDataPointAttributes(
      OtlpMetricVisitor metric, AggregateEntry entry, boolean error, boolean allTopLevel) {
    emitStringAttribute(metric, STATUS_CODE, error ? STATUS_CODE_ERROR : STATUS_CODE_OK);
    emitStringAttribute(metric, SPAN_NAME, entry.getResource());
    emitStringAttribute(metric, SPAN_KIND, canonicalSpanKind(entry.getSpanKind()));
    emitStringAttribute(metric, SERVICE_NAME, entry.getService());
    if (otelSemanticsMode) {
      return;
    }
    if (entry.hasHttpMethod()) {
      emitStringAttribute(metric, HTTP_REQUEST_METHOD, entry.getHttpMethod());
    }
    if (entry.getHttpStatusCode() != 0) {
      emitLongAttribute(metric, HTTP_RESPONSE_STATUS_CODE, entry.getHttpStatusCode());
    }
    if (entry.hasHttpEndpoint()) {
      emitStringAttribute(metric, HTTP_ROUTE, entry.getHttpEndpoint());
    }
    if (entry.hasGrpcStatusCode()) {
      emitStringAttribute(metric, RPC_RESPONSE_STATUS_CODE, entry.getGrpcStatusCode());
    }
    // additional_metric_tags support is still evolving/TBD across most tracer SDKs.
    for (UTF8BytesString additionalTag : entry.getAdditionalTags()) {
      emitAdditionalTag(metric, additionalTag);
    }
    emitStringAttribute(metric, DATADOG_OPERATION_NAME, entry.getOperationName());
    emitStringAttribute(metric, DATADOG_SPAN_TYPE, entry.getType());
    emitLongAttribute(metric, DATADOG_SPAN_TOP_LEVEL, allTopLevel ? 1L : 0L);
    emitBooleanAttribute(metric, DATADOG_IS_TRACE_ROOT, entry.isTraceRoot());
    if (entry.isSynthetics()) {
      emitStringAttribute(metric, DATADOG_ORIGIN, SYNTHETICS_ORIGIN);
    }
    emitPeerTags(metric, entry.getPeerTags());
  }

  private static void emitPeerTags(OtlpMetricVisitor metric, List<UTF8BytesString> peerTags) {
    if (peerTags.isEmpty()) {
      return;
    }
    List<String> peerTagValues = new ArrayList<>(peerTags.size());
    for (UTF8BytesString peerTag : peerTags) {
      peerTagValues.add(peerTag.toString());
    }
    metric.visitAttribute(STRING_ARRAY_ATTRIBUTE, DATADOG_PEER_TAGS, peerTagValues);
  }

  private static String canonicalSpanKind(CharSequence spanKind) {
    if (Tags.SPAN_KIND_SERVER.contentEquals(spanKind)) {
      return SPAN_KIND_SERVER;
    } else if (Tags.SPAN_KIND_CLIENT.contentEquals(spanKind)) {
      return SPAN_KIND_CLIENT;
    } else if (Tags.SPAN_KIND_PRODUCER.contentEquals(spanKind)) {
      return SPAN_KIND_PRODUCER;
    } else if (Tags.SPAN_KIND_CONSUMER.contentEquals(spanKind)) {
      return SPAN_KIND_CONSUMER;
    } else {
      return SPAN_KIND_INTERNAL;
    }
  }

  private static void emitAdditionalTag(OtlpMetricVisitor metric, UTF8BytesString additionalTag) {
    String packed = additionalTag.toString();
    int separator = packed.indexOf(':');
    if (separator <= 0) {
      return;
    }
    metric.visitAttribute(
        STRING_ATTRIBUTE, packed.substring(0, separator), packed.substring(separator + 1));
  }

  private static void emitStringAttribute(
      OtlpMetricVisitor metric, String key, @Nullable CharSequence value) {
    if (value != null) {
      metric.visitAttribute(STRING_ATTRIBUTE, key, value.toString());
    }
  }

  private static void emitLongAttribute(OtlpMetricVisitor metric, String key, long value) {
    metric.visitAttribute(LONG_ATTRIBUTE, key, value);
  }

  private static void emitBooleanAttribute(OtlpMetricVisitor metric, String key, boolean value) {
    metric.visitAttribute(BOOLEAN_ATTRIBUTE, key, value);
  }
}
