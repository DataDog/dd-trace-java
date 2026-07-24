package datadog.trace.core.otlp.metrics;

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
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.metrics.AggregateEntry;
import datadog.trace.common.metrics.AggregateEntryTestUtils;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link OtlpStatsMetricWriter}. Drives the writer through {@code startBucket} → {@code
 * add} → {@code finishBucket} with a capturing {@link OtlpSender}, then decodes the emitted
 * protobuf {@code ExportMetricsServiceRequest} ({@code MetricsData}) using protobuf's {@link
 * CodedInputStream}, reusing the decode idioms from {@code OtlpMetricsProtoTest}.
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
  private static final long BUCKET_START = SECONDS.toNanos(1_700_000_000L);
  private static final long BUCKET_DURATION = SECONDS.toNanos(10);

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

  private static AggregateEntry entry(
      String resource,
      boolean synthetic,
      int httpStatusCode,
      @Nullable String httpMethod,
      @Nullable String httpEndpoint,
      @Nullable String grpcStatusCode) {
    return AggregateEntryTestUtils.of(
        resource,
        "web",
        "servlet.request",
        null,
        "web",
        httpStatusCode,
        synthetic,
        true,
        "server",
        null,
        httpMethod,
        httpEndpoint,
        grpcStatusCode);
  }

  /** Build an entry and record {@code hits} ok durations of {@code durationNanos} each. */
  private static AggregateEntry okEntry(long durationNanos, int hits) {
    AggregateEntry e = entry("GET /users", false, 0, null, null, null);
    for (int i = 0; i < hits; i++) {
      AggregateEntryTestUtils.recordOk(e, durationNanos);
    }
    return e;
  }

  // ── decode helpers (adapted from OtlpMetricsProtoTest) ──────────────────────

  /**
   * A decoded histogram data point. Only the fields this test asserts on are decoded: the window
   * timestamps, the total count, and the attributes. Per-bucket contents (bucket_counts,
   * explicit_bounds), sum, min, and max are intentionally not decoded here.
   */
  private static final class DataPoint {
    long start;
    long end;
    long count;
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

  /**
   * Decodes the {@code Resource.attributes} ({@code ResourceMetrics.resource = 1} → {@code
   * Resource.attributes = 1}) into a key→value map, for asserting the {@code datadog.*} resource
   * attributes emitted in default mode.
   */
  private static Map<String, Object> decodeResourceAttributes(byte[] payload) throws IOException {
    CodedInputStream metricsData = CodedInputStream.newInstance(payload);
    metricsData.readTag(); // MetricsData.resource_metrics = 1
    CodedInputStream resourceMetrics = metricsData.readBytes().newCodedInput();
    Map<String, Object> attrs = new HashMap<>();
    while (!resourceMetrics.isAtEnd()) {
      int tag = resourceMetrics.readTag();
      if (WireFormat.getTagFieldNumber(tag) == 1) { // Resource
        CodedInputStream resource = resourceMetrics.readBytes().newCodedInput();
        while (!resource.isAtEnd()) {
          int rtag = resource.readTag();
          if (WireFormat.getTagFieldNumber(rtag) == 1) { // KeyValue attributes
            readKeyValue(resource.readBytes().newCodedInput(), attrs);
          } else {
            resource.skipField(rtag);
          }
        }
      } else {
        resourceMetrics.skipField(tag);
      }
    }
    return attrs;
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
        case 9: // attributes (KeyValue)
          readKeyValue(dp.readBytes().newCodedInput(), p.attributes);
          break;
        default: // sum, bucket_counts, explicit_bounds, min, max — not asserted here
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

  // ── writer driver ─────────────────────────────────────────────────────────

  /**
   * Drives the writer through one full {@code startBucket → add → finishBucket} cycle for {@code
   * entry} over the fixed {@link #BUCKET_START}/{@link #BUCKET_DURATION} window, asserts that
   * exactly one payload was sent, and returns the decoded metric.
   */
  private static DecodedMetric writeAndDecode(boolean otelSemanticsMode, AggregateEntry entry)
      throws IOException {
    CapturingSender sender = new CapturingSender();
    OtlpStatsMetricWriter writer = new OtlpStatsMetricWriter(sender, otelSemanticsMode, null);
    writer.startBucket(1, BUCKET_START, BUCKET_DURATION);
    writer.add(entry);
    writer.finishBucket();
    assertEquals(1, sender.sendCount, "exactly one payload sent");
    return decode(sender.lastPayload);
  }

  // ── test cases ──────────────────────────────────────────────────────────

  @Test
  void okOnlyEntryProducesExactlyOneDataPoint() throws IOException {
    DecodedMetric metric = writeAndDecode(false, okEntry(SECONDS.toNanos(1), 3));

    assertEquals("traces.span.sdk.metrics.duration", metric.name);
    assertEquals("s", metric.unit);
    assertEquals(TEMPORALITY_DELTA, metric.temporality, "histogram must be delta temporality");
    assertEquals(1, metric.dataPoints.size(), "ok-only → one data point");

    DataPoint dp = metric.dataPoints.get(0);
    assertEquals(BUCKET_START, dp.start, "start_time_unix_nano == startBucket start");
    assertEquals(BUCKET_START + BUCKET_DURATION, dp.end, "time_unix_nano == start + duration");
    assertEquals(3L, dp.count);
    assertFalse(dp.attributes.containsKey("status.code"), "ok point carries no status.code");
  }

  @Test
  void okPlusErrorEntryProducesTwoDataPointsWithErrorStatus() throws IOException {
    AggregateEntry e = entry("GET /users", false, 0, null, null, null);
    AggregateEntryTestUtils.recordOk(e, SECONDS.toNanos(1)); // ok
    AggregateEntryTestUtils.recordOk(e, SECONDS.toNanos(2)); // ok
    AggregateEntryTestUtils.recordError(e, SECONDS.toNanos(3)); // error

    DecodedMetric metric = writeAndDecode(false, e);
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
  }

  @Test
  void errorSeriesDoesNotLingerAfterClearWhenBucketHasOnlyOkHits() throws IOException {
    CapturingSender sender = new CapturingSender();
    OtlpStatsMetricWriter writer = new OtlpStatsMetricWriter(sender, false, null);

    // Bucket 1: the entry sees an error, so its error histogram is allocated and emits a point.
    AggregateEntry e = entry("GET /users", false, 0, null, null, null);
    AggregateEntryTestUtils.recordOk(e, SECONDS.toNanos(1)); // ok
    AggregateEntryTestUtils.recordError(e, SECONDS.toNanos(3)); // error

    writer.startBucket(1, BUCKET_START, BUCKET_DURATION);
    writer.add(e);
    writer.finishBucket();
    DecodedMetric bucket1 = decode(sender.lastPayload);
    assertEquals(2, bucket1.dataPoints.size(), "bucket with an error → ok+error data points");
    assertTrue(
        bucket1.dataPoints.stream()
            .anyMatch(dp -> "ERROR".equals(dp.attributes.get("status.code"))),
        "bucket 1 must carry a status.code=ERROR point");

    // Bucket 2: same entry, reset then only OK hits. errorLatencies survives clear() (cleared, not
    // nulled), so a non-null-but-empty histogram must NOT emit a phantom zero-count error series.
    AggregateEntryTestUtils.clear(e);
    AggregateEntryTestUtils.recordOk(e, SECONDS.toNanos(2)); // ok only

    writer.startBucket(2, BUCKET_START + BUCKET_DURATION, BUCKET_DURATION);
    writer.add(e);
    writer.finishBucket();
    DecodedMetric bucket2 = decode(sender.lastPayload);
    assertEquals(1, bucket2.dataPoints.size(), "ok-only bucket → exactly one data point");
    assertFalse(
        bucket2.dataPoints.get(0).attributes.containsKey("status.code"),
        "recovered entry must not emit a lingering status.code=ERROR series");
  }

  @Test
  void httpAndGrpcAttributesAppearOnlyWhenSet() throws IOException {
    AggregateEntry e = entry("GET /users/{id}", false, 200, "GET", "/users/{id}", "0");
    AggregateEntryTestUtils.recordOk(e, SECONDS.toNanos(1));

    DecodedMetric metric = writeAndDecode(false, e);
    assertEquals(1, metric.dataPoints.size());
    Map<String, Object> attrs = metric.dataPoints.get(0).attributes;

    assertEquals("GET", attrs.get("http.request.method"));
    assertEquals(200L, attrs.get("http.response.status_code"));
    assertEquals("/users/{id}", attrs.get("http.route"));
    assertEquals("0", attrs.get("rpc.response.status_code"));

    // a bare entry has none of these
    Map<String, Object> bareAttrs =
        writeAndDecode(false, okEntry(SECONDS.toNanos(1), 1)).dataPoints.get(0).attributes;
    assertFalse(bareAttrs.containsKey("http.request.method"));
    assertFalse(bareAttrs.containsKey("http.response.status_code"));
    assertFalse(bareAttrs.containsKey("http.route"));
    assertFalse(bareAttrs.containsKey("rpc.response.status_code"));
  }

  @Test
  void additionalMetricTagsEmittedAsStringAttributes() throws IOException {
    // Additional tags arrive on the entry pre-packed as "key:value" UTF8 strings in schema order;
    // the writer splits each at the first ':' and emits it as a plain OTLP string attribute keyed
    // by the tag name, in both semantics modes.
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
            null,
            new UTF8BytesString[] {
              UTF8BytesString.create("region:us-east-1"),
              UTF8BytesString.create("tenant_id:acme:corp")
            });
    AggregateEntryTestUtils.recordOk(e, SECONDS.toNanos(1));

    Map<String, Object> attrs = writeAndDecode(false, e).dataPoints.get(0).attributes;
    assertEquals("us-east-1", attrs.get("region"));
    // value may itself contain ':' — only the first ':' separates key from value
    assertEquals("acme:corp", attrs.get("tenant_id"));
  }

  @Test
  void additionalMetricTagsEmittedInOtelSemanticsMode() throws IOException {
    // Unlike datadog.* attributes, additional tags are user-configured dimensions and are emitted
    // in otel-semantics mode too.
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
            null,
            new UTF8BytesString[] {UTF8BytesString.create("region:us-east-1")});
    AggregateEntryTestUtils.recordOk(e, SECONDS.toNanos(1));

    Map<String, Object> attrs = writeAndDecode(true, e).dataPoints.get(0).attributes;
    assertEquals("us-east-1", attrs.get("region"));
    assertFalse(
        attrs.containsKey("datadog.operation.name"), "datadog.* still absent in otel-semantics");
  }

  @Test
  void malformedAdditionalTagsAreSkipped() throws IOException {
    // Defensive: a slot with no ':', an empty key, or an empty value is dropped rather than emitted
    // as a malformed attribute.
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
            null,
            new UTF8BytesString[] {
              UTF8BytesString.create("noseparator"),
              UTF8BytesString.create(":emptykey"),
              UTF8BytesString.create("emptyvalue:"),
              UTF8BytesString.create("region:us-east-1")
            });
    AggregateEntryTestUtils.recordOk(e, SECONDS.toNanos(1));

    Map<String, Object> attrs = writeAndDecode(false, e).dataPoints.get(0).attributes;
    assertEquals("us-east-1", attrs.get("region"), "well-formed tag still emitted");
    assertFalse(attrs.containsKey("noseparator"), "no-separator slot skipped");
    assertFalse(attrs.containsKey(""), "empty-key slot skipped");
    assertFalse(attrs.containsKey("emptyvalue"), "empty-value slot skipped");
  }

  @Test
  void serviceNameEmittedOnlyForNonDefaultService() throws IOException {
    CapturingSender sender = new CapturingSender();
    // The configured default service ("web") is reported on the resource; only a span whose service
    // differs from it repeats service.name on its own data point.
    OtlpStatsMetricWriter writer = new OtlpStatsMetricWriter(sender, false, "web");

    long start = SECONDS.toNanos(1_700_000_000L);
    writer.startBucket(2, start, SECONDS.toNanos(10));
    writer.add(serviceEntry("web.request", "web")); // default service
    writer.add(serviceEntry("db.query", "postgres")); // custom service
    writer.finishBucket();

    DecodedMetric metric = decode(sender.lastPayload);
    assertEquals(2, metric.dataPoints.size());

    Map<String, Object> defaultAttrs = null;
    Map<String, Object> customAttrs = null;
    for (DataPoint dp : metric.dataPoints) {
      if ("db.query".equals(dp.attributes.get("datadog.operation.name"))) {
        customAttrs = dp.attributes;
      } else {
        defaultAttrs = dp.attributes;
      }
    }
    assertNotNull(customAttrs, "custom-service data point present");
    assertNotNull(defaultAttrs, "default-service data point present");
    assertEquals(
        "postgres",
        customAttrs.get("service.name"),
        "non-default service is carried on its own data point");
    assertFalse(
        defaultAttrs.containsKey("service.name"),
        "default service must not be repeated on its data point");
  }

  /** An ok-only entry on the given service and operation name, recording a single 1s hit. */
  private static AggregateEntry serviceEntry(String operationName, String service) {
    AggregateEntry e =
        AggregateEntryTestUtils.of(
            "GET /users",
            service,
            operationName,
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
    AggregateEntryTestUtils.recordOk(e, SECONDS.toNanos(1));
    return e;
  }

  @Test
  void emptyBucketSendsNothing() {
    CapturingSender sender = new CapturingSender();
    OtlpStatsMetricWriter writer = new OtlpStatsMetricWriter(sender, false, null);

    writer.startBucket(0, BUCKET_START, BUCKET_DURATION);
    writer.finishBucket(); // no add()

    assertEquals(0, sender.sendCount, "empty bucket must not invoke send");
    assertNull(sender.lastPayload);
  }

  @Test
  void nullSenderDoesNotThrowOnNonEmptyBucket() {
    // mirrors the HTTP_JSON path where createSender(config) returns null.
    OtlpStatsMetricWriter writer = new OtlpStatsMetricWriter(null, false, null);
    writer.startBucket(1, BUCKET_START, BUCKET_DURATION);
    writer.add(okEntry(SECONDS.toNanos(1), 2));
    try {
      writer.finishBucket();
    } catch (Exception ex) {
      fail("finishBucket must not throw with a null sender, but threw: " + ex);
    }
  }

  @Test
  void defaultModeCarriesDatadogAttributes() throws IOException {
    // use an entry where all hits are top-level: OR in TOP_LEVEL_TAG
    AggregateEntry e = entry("servlet.request", false, 0, null, null, null);
    AggregateEntryTestUtils.recordTopLevel(e, SECONDS.toNanos(1));

    Map<String, Object> attrs = writeAndDecode(false, e).dataPoints.get(0).attributes;
    assertTrue(
        attrs.containsKey("datadog.operation.name"), "operation name present in default mode");
    assertTrue(attrs.containsKey("datadog.span.type"), "span type present in default mode");
    assertTrue(
        attrs.containsKey("datadog.span.top_level"), "span top-level present in default mode");
    assertEquals(1L, attrs.get("datadog.span.top_level"), "all hits top-level → 1");
    // OTel-semconv attrs are present in both modes
    assertTrue(attrs.containsKey("span.name"), "span.name present in both modes");
    // datadog.origin presence/absence is covered by defaultModeEmitsSyntheticOrigin
  }

  /**
   * In default mode a synthetic entry emits {@code datadog.origin = "synthetics"}; a non-synthetic
   * entry omits the attribute. Origin has collapsed to a boolean {@code synthetic} flag upstream,
   * so {@code "synthetics"} is the only origin value that can reach the writer.
   */
  @ParameterizedTest(name = "synthetic={0} → datadog.origin={1}")
  @CsvSource(
      nullValues = "NULL",
      value = {"false, NULL", "true, synthetics"})
  void defaultModeEmitsSyntheticOrigin(boolean synthetic, String expectedOrigin)
      throws IOException {
    AggregateEntry e = entry("servlet.request", synthetic, 0, null, null, null);
    AggregateEntryTestUtils.recordOk(e, SECONDS.toNanos(1));

    Map<String, Object> attrs = writeAndDecode(false, e).dataPoints.get(0).attributes;
    if (expectedOrigin == null) {
      assertFalse(attrs.containsKey("datadog.origin"), "non-synthetic → datadog.origin absent");
    } else {
      assertEquals(expectedOrigin, attrs.get("datadog.origin"), "synthetic → datadog.origin");
    }
  }

  @Test
  void otelSemanticsModeOmitsDatadogAttributes() throws IOException {
    // otelSemanticsMode = true → datadog.* must be absent
    Map<String, Object> attrs =
        writeAndDecode(true, okEntry(SECONDS.toNanos(1), 1)).dataPoints.get(0).attributes;
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

  @Test
  void snapshotsEntryDataBeforeAggregatorClearsIt() throws IOException {
    // The aggregator clears each entry's per-interval data immediately after add() returns
    // (Aggregator#report), before finishBucket() runs. The writer must snapshot the latency data
    // (and the top-level count) at add() time; if it deferred reading to finishBucket() it would
    // encode the already-cleared (empty, zero-count) entry.
    CapturingSender sender = new CapturingSender();
    OtlpStatsMetricWriter writer = new OtlpStatsMetricWriter(sender, false, null);

    AggregateEntry e = entry("servlet.request", false, 0, null, null, null);
    AggregateEntryTestUtils.recordTopLevel(e, SECONDS.toNanos(1));
    AggregateEntryTestUtils.recordTopLevel(e, SECONDS.toNanos(1));
    AggregateEntryTestUtils.recordTopLevel(e, SECONDS.toNanos(1));

    writer.startBucket(1, BUCKET_START, BUCKET_DURATION);
    writer.add(e);
    AggregateEntryTestUtils.clear(e); // mimic Aggregator#report clearing right after add()
    writer.finishBucket();

    assertEquals(1, sender.sendCount, "cleared-after-add entry must still emit its snapshot");
    DecodedMetric metric = decode(sender.lastPayload);
    assertEquals(1, metric.dataPoints.size());
    DataPoint dp = metric.dataPoints.get(0);
    assertEquals(3L, dp.count, "count must reflect the pre-clear snapshot, not the cleared entry");
    assertEquals(
        1L, dp.attributes.get("datadog.span.top_level"), "all pre-clear hits were top-level");
  }

  // ── resource attributes (datadog.runtime_id / process tags) ────────────────

  @Test
  void defaultModeResourceCarriesRuntimeId() throws IOException {
    // runtime-id is enabled by default, so default-mode payloads carry datadog.runtime_id on the
    // Resource.
    CapturingSender sender = new CapturingSender();
    OtlpStatsMetricWriter writer = new OtlpStatsMetricWriter(sender, false, null);
    writer.startBucket(1, SECONDS.toNanos(1_700_000_000L), SECONDS.toNanos(10));
    writer.add(okEntry(SECONDS.toNanos(1), 1));
    writer.finishBucket();

    Map<String, Object> resourceAttrs = decodeResourceAttributes(sender.lastPayload);
    assertTrue(
        resourceAttrs.containsKey("datadog.runtime_id"),
        "default mode resource carries datadog.runtime_id");
    Object runtimeId = resourceAttrs.get("datadog.runtime_id");
    assertNotNull(runtimeId, "runtime id value present");
    assertFalse(runtimeId.toString().isEmpty(), "runtime id value non-empty");
  }

  @Test
  void otelSemanticsModeResourceOmitsDatadogAttributes() throws IOException {
    CapturingSender sender = new CapturingSender();
    OtlpStatsMetricWriter writer = new OtlpStatsMetricWriter(sender, true, null);
    writer.startBucket(1, SECONDS.toNanos(1_700_000_000L), SECONDS.toNanos(10));
    writer.add(okEntry(SECONDS.toNanos(1), 1));
    writer.finishBucket();

    Map<String, Object> resourceAttrs = decodeResourceAttributes(sender.lastPayload);
    assertFalse(
        resourceAttrs.containsKey("datadog.runtime_id"),
        "otel-semantics mode resource omits datadog.runtime_id");
    for (String key : resourceAttrs.keySet()) {
      assertFalse(
          key.startsWith("datadog."),
          "otel-semantics mode resource has no datadog.* attrs: " + key);
    }
  }
}
