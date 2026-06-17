package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import datadog.metrics.api.Histograms;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OtlpStatsMetricWriter}. Drives the writer through {@code startBucket} → {@code
 * add} → {@code finishBucket} with a capturing {@link OtlpSender}, then decodes the emitted
 * protobuf {@code ExportMetricsServiceRequest} ({@code MetricsData}) using protobuf's {@link
 * CodedInputStream}, reusing the decode idioms from {@code OtlpMetricsProtoTest}.
 *
 * <p>Lives in {@code datadog.trace.common.metrics} to reach the package-private testing constructor
 * {@code OtlpStatsMetricWriter(OtlpSender)} and the {@code AggregateEntryTestUtils} factory.
 *
 * <p>Wire layout (OTLP metrics proto):
 *
 * <pre>
 *   MetricsData        { ResourceMetrics resource_metrics = 1; }
 *   ResourceMetrics    { Resource resource = 1; ScopeMetrics scope_metrics = 2; }
 *   ScopeMetrics       { InstrumentationScope scope = 1; Metric metrics = 2; }
 *   Metric             { string name = 1; string unit = 3; Histogram histogram = 9; }
 *   Histogram          { HistogramDataPoint data_points = 1; AggregationTemporality = 2; }
 *   HistogramDataPoint { fixed64 start = 2; fixed64 time = 3; fixed64 count = 4; double sum = 5;
 *                        fixed64 bucket_counts = 6; double explicit_bounds = 7;
 *                        KeyValue attributes = 9; double min = 11; double max = 12; }
 * </pre>
 */
class OtlpStatsMetricWriterTest {

  private static final int TEMPORALITY_DELTA = 1;

  @BeforeAll
  static void registerHistogramFactory() {
    Histograms.register(DDSketchHistograms.FACTORY);
  }

  // ── capturing sender ──────────────────────────────────────────────────────

  private static final class CapturingSender implements OtlpSender {
    int sendCount;
    byte[] lastPayload;

    @Override
    public void send(OtlpPayload payload) {
      sendCount++;
      java.nio.ByteBuffer content = payload.getContent();
      byte[] bytes = new byte[content.remaining()];
      content.get(bytes);
      lastPayload = bytes;
    }

    @Override
    public void shutdown() {}
  }

  // ── entry builders ──────────────────────────────────────────────────────

  /** Build an entry and record {@code hits} ok durations of {@code durationNanos} each. */
  private static AggregateEntry okEntry(long durationNanos, int hits) {
    AggregateEntry e =
        AggregateEntryTestUtils.of(
            "GET /users",
            "web",
            "servlet.request",
            null,
            "web",
            0,
            false,
            true,
            "server",
            null,
            null,
            null,
            null);
    for (int i = 0; i < hits; i++) {
      e.recordOneDuration(durationNanos);
    }
    return e;
  }

  // ── decode helpers (adapted from OtlpMetricsProtoTest) ──────────────────────

  /** A decoded histogram data point. */
  private static final class DataPoint {
    long start;
    long end;
    long count;
    double sum;
    double min;
    double max;
    final List<Long> bucketCounts = new ArrayList<>();
    final List<Double> bounds = new ArrayList<>();
    final Map<String, Object> attributes = new HashMap<>();
  }

  /** A decoded metric: name, unit, temporality, and its histogram data points. */
  private static final class DecodedMetric {
    String name;
    String unit;
    int temporality = -1;
    final List<DataPoint> dataPoints = new ArrayList<>();
  }

  /** Decodes a full {@code MetricsData} payload into the single histogram metric it carries. */
  private static DecodedMetric decode(byte[] payload) throws IOException {
    CodedInputStream metricsData = CodedInputStream.newInstance(payload);
    int metricsTag = metricsData.readTag();
    assertEquals(1, WireFormat.getTagFieldNumber(metricsTag), "MetricsData.resource_metrics = 1");
    CodedInputStream resourceMetrics = metricsData.readBytes().newCodedInput();
    assertTrue(metricsData.isAtEnd(), "expected exactly one ResourceMetrics");

    DecodedMetric metric = null;
    while (!resourceMetrics.isAtEnd()) {
      int tag = resourceMetrics.readTag();
      int field = WireFormat.getTagFieldNumber(tag);
      if (field == 2) { // ScopeMetrics
        metric = parseScopeMetrics(resourceMetrics.readBytes().newCodedInput());
      } else {
        resourceMetrics.skipField(tag); // Resource (field 1) etc.
      }
    }
    assertNotNull(metric, "no ScopeMetrics found");
    return metric;
  }

  private static DecodedMetric parseScopeMetrics(CodedInputStream scopeMetrics) throws IOException {
    DecodedMetric metric = null;
    while (!scopeMetrics.isAtEnd()) {
      int tag = scopeMetrics.readTag();
      if (WireFormat.getTagFieldNumber(tag) == 2) { // Metric
        metric = parseMetric(scopeMetrics.readBytes().newCodedInput());
      } else {
        scopeMetrics.skipField(tag);
      }
    }
    return metric;
  }

  private static DecodedMetric parseMetric(CodedInputStream m) throws IOException {
    DecodedMetric metric = new DecodedMetric();
    while (!m.isAtEnd()) {
      int tag = m.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1:
          metric.name = m.readString();
          break;
        case 3:
          metric.unit = m.readString();
          break;
        case 9: // Histogram
          parseHistogram(m.readBytes().newCodedInput(), metric);
          break;
        default:
          m.skipField(tag);
      }
    }
    return metric;
  }

  private static void parseHistogram(CodedInputStream h, DecodedMetric metric) throws IOException {
    while (!h.isAtEnd()) {
      int tag = h.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1: // HistogramDataPoint (repeated)
          metric.dataPoints.add(parseDataPoint(h.readBytes().newCodedInput()));
          break;
        case 2: // aggregation_temporality
          metric.temporality = h.readEnum();
          break;
        default:
          h.skipField(tag);
      }
    }
  }

  private static DataPoint parseDataPoint(CodedInputStream dp) throws IOException {
    DataPoint p = new DataPoint();
    while (!dp.isAtEnd()) {
      int tag = dp.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 2:
          p.start = dp.readFixed64();
          break;
        case 3:
          p.end = dp.readFixed64();
          break;
        case 4:
          p.count = dp.readFixed64();
          break;
        case 5:
          p.sum = dp.readDouble();
          break;
        case 6:
          p.bucketCounts.add(dp.readFixed64());
          break;
        case 7:
          p.bounds.add(dp.readDouble());
          break;
        case 9: // attributes (KeyValue)
          readKeyValue(dp.readBytes().newCodedInput(), p.attributes);
          break;
        case 11:
          p.min = dp.readDouble();
          break;
        case 12:
          p.max = dp.readDouble();
          break;
        default:
          dp.skipField(tag);
      }
    }
    return p;
  }

  /**
   * Reads a {@code KeyValue} into {@code out}: key (field 1) → value. Value is an {@code AnyValue}
   * (field 2); we decode string (field 1) and int (field 3) variants used by this writer.
   */
  private static void readKeyValue(CodedInputStream kv, Map<String, Object> out)
      throws IOException {
    String key = null;
    Object value = null;
    while (!kv.isAtEnd()) {
      int tag = kv.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1:
          key = kv.readString();
          break;
        case 2: // AnyValue
          value = readAnyValue(kv.readBytes().newCodedInput());
          break;
        default:
          kv.skipField(tag);
      }
    }
    if (key != null) {
      out.put(key, value);
    }
  }

  private static Object readAnyValue(CodedInputStream any) throws IOException {
    Object value = null;
    while (!any.isAtEnd()) {
      int tag = any.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1: // string_value
          value = any.readString();
          break;
        case 3: // int_value
          value = any.readInt64();
          break;
        default:
          any.skipField(tag);
      }
    }
    return value;
  }

  // ── test cases ──────────────────────────────────────────────────────────

  @Test
  void okOnlyEntryProducesExactlyOneDataPoint() throws IOException {
    CapturingSender sender = new CapturingSender();
    OtlpStatsMetricWriter writer = new OtlpStatsMetricWriter(sender);

    long start = SECONDS.toNanos(1_700_000_000L);
    long duration = SECONDS.toNanos(10);
    writer.startBucket(1, start, duration);
    writer.add(okEntry(SECONDS.toNanos(1), 3));
    writer.finishBucket();

    assertEquals(1, sender.sendCount, "exactly one payload sent");
    DecodedMetric metric = decode(sender.lastPayload);

    assertEquals("traces.span.sdk.metrics.duration", metric.name);
    assertEquals("s", metric.unit);
    assertEquals(TEMPORALITY_DELTA, metric.temporality, "histogram must be delta temporality");
    assertEquals(1, metric.dataPoints.size(), "ok-only → one data point");

    DataPoint dp = metric.dataPoints.get(0);
    assertEquals(start, dp.start, "start_time_unix_nano == startBucket start");
    assertEquals(start + duration, dp.end, "time_unix_nano == start + duration");
    assertEquals(3L, dp.count);
    assertEquals(3L, sumBuckets(dp), "bucket counts sum to total count");
    assertEquals(17, dp.bucketCounts.size(), "17 OTLP buckets");
    assertFalse(dp.attributes.containsKey("status.code"), "ok point carries no status.code");
  }

  @Test
  void okPlusErrorEntryProducesTwoDataPointsWithErrorStatus() throws IOException {
    CapturingSender sender = new CapturingSender();
    OtlpStatsMetricWriter writer = new OtlpStatsMetricWriter(sender);

    AggregateEntry e =
        AggregateEntryTestUtils.of(
            "GET /users",
            "web",
            "servlet.request",
            null,
            "web",
            0,
            false,
            true,
            "server",
            null,
            null,
            null,
            null);
    e.recordOneDuration(SECONDS.toNanos(1)); // ok
    e.recordOneDuration(SECONDS.toNanos(2)); // ok
    e.recordOneDuration(SECONDS.toNanos(3) | AggregateEntry.ERROR_TAG); // error

    long start = SECONDS.toNanos(1_700_000_000L);
    long duration = SECONDS.toNanos(10);
    writer.startBucket(1, start, duration);
    writer.add(e);
    writer.finishBucket();

    DecodedMetric metric = decode(sender.lastPayload);
    assertEquals(2, metric.dataPoints.size(), "ok+error → two data points");

    long okCount = 0;
    long errorCount = 0;
    DataPoint errorPoint = null;
    DataPoint okPoint = null;
    for (DataPoint dp : metric.dataPoints) {
      if ("ERROR".equals(dp.attributes.get("status.code"))) {
        errorPoint = dp;
        errorCount = dp.count;
      } else {
        okPoint = dp;
        okCount = dp.count;
      }
    }
    assertNotNull(errorPoint, "one data point must carry status.code=ERROR");
    assertNotNull(okPoint, "one data point must omit status.code");
    assertEquals(e.getOkLatencies().getCount(), (double) okCount, 1e-9);
    assertEquals(e.getErrorLatencies().getCount(), (double) errorCount, 1e-9);
    assertEquals(okCount, sumBuckets(okPoint));
    assertEquals(errorCount, sumBuckets(errorPoint));
  }

  @Test
  void httpAndGrpcAttributesAppearOnlyWhenSet() throws IOException {
    CapturingSender sender = new CapturingSender();
    OtlpStatsMetricWriter writer = new OtlpStatsMetricWriter(sender);

    AggregateEntry e =
        AggregateEntryTestUtils.of(
            "GET /users/{id}",
            "web",
            "servlet.request",
            null,
            "web",
            200,
            false,
            true,
            "server",
            null,
            "GET",
            "/users/{id}",
            "0");
    e.recordOneDuration(SECONDS.toNanos(1));

    long start = SECONDS.toNanos(1_700_000_000L);
    writer.startBucket(1, start, SECONDS.toNanos(10));
    writer.add(e);
    writer.finishBucket();

    DecodedMetric metric = decode(sender.lastPayload);
    assertEquals(1, metric.dataPoints.size());
    Map<String, Object> attrs = metric.dataPoints.get(0).attributes;

    assertEquals("GET", attrs.get("http.request.method"));
    assertEquals(200L, attrs.get("http.response.status_code"));
    assertEquals("/users/{id}", attrs.get("http.route"));
    assertEquals("0", attrs.get("rpc.response.status_code"));

    // a bare entry has none of these
    CapturingSender sender2 = new CapturingSender();
    OtlpStatsMetricWriter writer2 = new OtlpStatsMetricWriter(sender2);
    writer2.startBucket(1, start, SECONDS.toNanos(10));
    writer2.add(okEntry(SECONDS.toNanos(1), 1));
    writer2.finishBucket();
    Map<String, Object> bareAttrs = decode(sender2.lastPayload).dataPoints.get(0).attributes;
    assertFalse(bareAttrs.containsKey("http.request.method"));
    assertFalse(bareAttrs.containsKey("http.response.status_code"));
    assertFalse(bareAttrs.containsKey("http.route"));
    assertFalse(bareAttrs.containsKey("rpc.response.status_code"));
  }

  @Test
  void emptyBucketSendsNothing() {
    CapturingSender sender = new CapturingSender();
    OtlpStatsMetricWriter writer = new OtlpStatsMetricWriter(sender);

    writer.startBucket(0, SECONDS.toNanos(1_700_000_000L), SECONDS.toNanos(10));
    writer.finishBucket(); // no add()

    assertEquals(0, sender.sendCount, "empty bucket must not invoke send");
    assertNull(sender.lastPayload);
  }

  @Test
  void nullSenderDoesNotThrowOnNonEmptyBucket() {
    // mirrors the HTTP_JSON path where createSender(config) returns null.
    OtlpStatsMetricWriter writer = new OtlpStatsMetricWriter((OtlpSender) null);
    writer.startBucket(1, SECONDS.toNanos(1_700_000_000L), SECONDS.toNanos(10));
    writer.add(okEntry(SECONDS.toNanos(1), 2));
    try {
      writer.finishBucket();
    } catch (Exception ex) {
      fail("finishBucket must not throw with a null sender, but threw: " + ex);
    }
  }

  // ── step 4: attribute modes ──────────────────────────────────────────────

  @Test
  void defaultModeCarriesDatadogAttributes() throws IOException {
    CapturingSender sender = new CapturingSender();
    // otelSemanticsMode = false → datadog.* should be present
    OtlpStatsMetricWriter writer = new OtlpStatsMetricWriter(sender, false);
    writer.startBucket(1, SECONDS.toNanos(1_700_000_000L), SECONDS.toNanos(10));
    // use an entry where all hits are top-level: OR in TOP_LEVEL_TAG
    AggregateEntry e =
        AggregateEntryTestUtils.of(
            "servlet.request",
            "web",
            "servlet.request",
            null,
            "web",
            0,
            false,
            true,
            "server",
            null,
            null,
            null,
            null);
    e.recordOneDuration(SECONDS.toNanos(1) | AggregateEntry.TOP_LEVEL_TAG);
    writer.add(e);
    writer.finishBucket();

    Map<String, Object> attrs = decode(sender.lastPayload).dataPoints.get(0).attributes;
    assertTrue(
        attrs.containsKey("datadog.operation.name"), "operation name present in default mode");
    assertTrue(attrs.containsKey("datadog.span.type"), "span type present in default mode");
    assertTrue(
        attrs.containsKey("datadog.span.top_level"), "span top-level present in default mode");
    assertEquals(1L, attrs.get("datadog.span.top_level"), "all hits top-level → 1");
    // OTel-semconv attrs are present in both modes
    assertTrue(attrs.containsKey("span.name"), "span.name present in both modes");
    assertFalse(attrs.containsKey("datadog.origin"), "non-synthetic entry has no datadog.origin");
  }

  @Test
  void defaultModeCarriesSyntheticsOrigin() throws IOException {
    CapturingSender sender = new CapturingSender();
    OtlpStatsMetricWriter writer = new OtlpStatsMetricWriter(sender, false);
    writer.startBucket(1, SECONDS.toNanos(1_700_000_000L), SECONDS.toNanos(10));
    AggregateEntry e =
        AggregateEntryTestUtils.of(
            "servlet.request",
            "web",
            "servlet.request",
            null,
            "web",
            0,
            true, // synthetic=true
            true,
            "server",
            null,
            null,
            null,
            null);
    e.recordOneDuration(SECONDS.toNanos(1));
    writer.add(e);
    writer.finishBucket();

    Map<String, Object> attrs = decode(sender.lastPayload).dataPoints.get(0).attributes;
    assertEquals(
        "synthetics", attrs.get("datadog.origin"), "synthetic entry carries datadog.origin");
  }

  @Test
  void otelSemanticsModeOmitsDatadogAttributes() throws IOException {
    CapturingSender sender = new CapturingSender();
    // otelSemanticsMode = true → datadog.* must be absent
    OtlpStatsMetricWriter writer = new OtlpStatsMetricWriter(sender, true);
    writer.startBucket(1, SECONDS.toNanos(1_700_000_000L), SECONDS.toNanos(10));
    writer.add(okEntry(SECONDS.toNanos(1), 1));
    writer.finishBucket();

    Map<String, Object> attrs = decode(sender.lastPayload).dataPoints.get(0).attributes;
    assertFalse(
        attrs.containsKey("datadog.operation.name"),
        "operation name absent in otel-semantics mode");
    assertFalse(attrs.containsKey("datadog.span.type"), "span type absent in otel-semantics mode");
    assertFalse(
        attrs.containsKey("datadog.span.top_level"),
        "span top-level absent in otel-semantics mode");
    assertFalse(attrs.containsKey("datadog.origin"), "origin absent in otel-semantics mode");
    // OTel-semconv attrs must still be present
    assertTrue(attrs.containsKey("span.name"), "span.name present even in otel-semantics mode");
  }

  private static long sumBuckets(DataPoint dp) {
    long total = 0;
    for (long c : dp.bucketCounts) {
      total += c;
    }
    return total;
  }
}
