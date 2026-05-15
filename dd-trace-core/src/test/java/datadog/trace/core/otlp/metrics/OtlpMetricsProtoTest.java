package datadog.trace.core.otlp.metrics;

import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.COUNTER;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.GAUGE;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.HISTOGRAM;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.OBSERVABLE_COUNTER;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.OBSERVABLE_GAUGE;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.OBSERVABLE_UP_DOWN_COUNTER;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.UP_DOWN_COUNTER;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.BOOLEAN_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.DOUBLE_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.LONG_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ATTRIBUTE;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentType;
import datadog.trace.bootstrap.otlp.metrics.OtlpDataPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpDoublePoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpHistogramPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpLongPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricVisitor;
import datadog.trace.bootstrap.otlp.metrics.OtlpScopedMetricsVisitor;
import datadog.trace.core.otlp.common.OtlpPayload;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link OtlpMetricsProto} via {@link OtlpMetricsProtoCollector#collectMetrics}.
 *
 * <p>Each test case drives the collector through its visitor API, drains the resulting chunked
 * payload into a contiguous byte array, and then parses it back using protobuf's {@link
 * CodedInputStream} to verify the wire encoding against the OpenTelemetry metrics proto schema.
 *
 * <p>Relevant proto field numbers (from {@code opentelemetry/proto/metrics/v1/metrics.proto}):
 *
 * <pre>
 *   MetricsData        { ResourceMetrics resource_metrics = 1; }
 *   ResourceMetrics    { Resource resource = 1; ScopeMetrics scope_metrics = 2; }
 *   ScopeMetrics       { InstrumentationScope scope = 1; Metric metrics = 2; string schema_url = 3; }
 *   InstrumentationScope { string name = 1; string version = 2; }
 *   Metric             { string name = 1; string description = 2; string unit = 3;
 *                        Gauge gauge = 5; Sum sum = 7; Histogram histogram = 9; }
 *   Gauge              { NumberDataPoint data_points = 1; }
 *   Sum                { NumberDataPoint data_points = 1; AggregationTemporality ... = 2; bool is_monotonic = 3; }
 *   Histogram          { HistogramDataPoint data_points = 1; AggregationTemporality ... = 2; }
 *   NumberDataPoint    { fixed64 start_time_unix_nano = 2; fixed64 time_unix_nano = 3;
 *                        double as_double = 4; sfixed64 as_int = 6; KeyValue attributes = 7; }
 *   HistogramDataPoint { fixed64 start_time_unix_nano = 2; fixed64 time_unix_nano = 3;
 *                        fixed64 count = 4; double sum = 5; fixed64 bucket_counts = 6;
 *                        double explicit_bounds = 7; KeyValue attributes = 9;
 *                        double min = 11; double max = 12; }
 * </pre>
 */
class OtlpMetricsProtoTest {

  // ── spec classes (test-data descriptors) ──────────────────────────────────

  static final class ScopeSpec {
    final String name;
    final String version; // null → absent from wire
    final String schemaUrl; // null → absent from wire
    final List<MetricSpec> metrics;

    ScopeSpec(String name, String version, String schemaUrl, List<MetricSpec> metrics) {
      this.name = name;
      this.version = version;
      this.schemaUrl = schemaUrl;
      this.metrics = metrics;
    }

    OtelInstrumentationScope toScope() {
      return new OtelInstrumentationScope(name, version, schemaUrl);
    }
  }

  static final class MetricSpec {
    final String name;
    final String description; // null → absent from wire
    final String unit; // null → absent from wire
    final OtelInstrumentType type;
    final boolean longValues;
    final OtlpDataPoint point;
    final List<AttrSpec> attrs;

    MetricSpec(
        String name,
        String description,
        String unit,
        OtelInstrumentType type,
        boolean longValues,
        OtlpDataPoint point,
        List<AttrSpec> attrs) {
      this.name = name;
      this.description = description;
      this.unit = unit;
      this.type = type;
      this.longValues = longValues;
      this.point = point;
      this.attrs = attrs;
    }

    OtelInstrumentDescriptor toDescriptor() {
      return new OtelInstrumentDescriptor(name, type, longValues, description, unit);
    }
  }

  static final class AttrSpec {
    final int type;
    final String key;
    final Object value;

    AttrSpec(int type, String key, Object value) {
      this.type = type;
      this.key = key;
      this.value = value;
    }
  }

  private static final class ParsedScopeMetrics {
    final String name;
    final String version;
    final String schemaUrl;
    final List<byte[]> metricBlobs;

    ParsedScopeMetrics(String name, String version, String schemaUrl, List<byte[]> metricBlobs) {
      this.name = name;
      this.version = version;
      this.schemaUrl = schemaUrl;
      this.metricBlobs = metricBlobs;
    }
  }

  // ── shorthand builders ────────────────────────────────────────────────────

  private static ScopeSpec scope(String name, MetricSpec... metrics) {
    return new ScopeSpec(name, null, null, asList(metrics));
  }

  private static ScopeSpec scopeFull(
      String name, String version, String schemaUrl, MetricSpec... metrics) {
    return new ScopeSpec(name, version, schemaUrl, asList(metrics));
  }

  private static MetricSpec counterLong(String name, long value, AttrSpec... attrs) {
    return new MetricSpec(name, null, null, COUNTER, true, longPoint(value), asList(attrs));
  }

  private static MetricSpec counterLongFull(
      String name, String desc, String unit, long value, AttrSpec... attrs) {
    return new MetricSpec(name, desc, unit, COUNTER, true, longPoint(value), asList(attrs));
  }

  private static MetricSpec counterDouble(String name, double value, AttrSpec... attrs) {
    return new MetricSpec(name, null, null, COUNTER, false, doublePoint(value), asList(attrs));
  }

  private static MetricSpec gaugeLong(String name, long value) {
    return new MetricSpec(name, null, null, GAUGE, true, longPoint(value), emptyList());
  }

  private static MetricSpec gaugeDouble(String name, double value) {
    return new MetricSpec(name, null, null, GAUGE, false, doublePoint(value), emptyList());
  }

  private static MetricSpec upDownLong(String name, long value, AttrSpec... attrs) {
    return new MetricSpec(name, null, null, UP_DOWN_COUNTER, true, longPoint(value), asList(attrs));
  }

  private static MetricSpec upDownDouble(String name, double value, AttrSpec... attrs) {
    return new MetricSpec(
        name, null, null, UP_DOWN_COUNTER, false, doublePoint(value), asList(attrs));
  }

  private static MetricSpec observableGaugeLong(String name, long value) {
    return new MetricSpec(name, null, null, OBSERVABLE_GAUGE, true, longPoint(value), emptyList());
  }

  private static MetricSpec observableGaugeDouble(String name, double value) {
    return new MetricSpec(
        name, null, null, OBSERVABLE_GAUGE, false, doublePoint(value), emptyList());
  }

  private static MetricSpec observableCounterLong(String name, long value) {
    return new MetricSpec(
        name, null, null, OBSERVABLE_COUNTER, true, longPoint(value), emptyList());
  }

  private static MetricSpec observableCounterDouble(String name, double value) {
    return new MetricSpec(
        name, null, null, OBSERVABLE_COUNTER, false, doublePoint(value), emptyList());
  }

  private static MetricSpec observableUpDownCounterLong(String name, long value) {
    return new MetricSpec(
        name, null, null, OBSERVABLE_UP_DOWN_COUNTER, true, longPoint(value), emptyList());
  }

  private static MetricSpec observableUpDownCounterDouble(String name, double value) {
    return new MetricSpec(
        name, null, null, OBSERVABLE_UP_DOWN_COUNTER, false, doublePoint(value), emptyList());
  }

  private static MetricSpec histogram(
      String name,
      double count,
      List<Double> bounds,
      List<Double> counts,
      double sum,
      double min,
      double max,
      AttrSpec... attrs) {
    return new MetricSpec(
        name,
        null,
        null,
        HISTOGRAM,
        false,
        histogramPoint(count, bounds, counts, sum, min, max),
        asList(attrs));
  }

  private static AttrSpec strAttr(String key, String value) {
    return new AttrSpec(STRING_ATTRIBUTE, key, value);
  }

  private static AttrSpec longAttr(String key, long value) {
    return new AttrSpec(LONG_ATTRIBUTE, key, value);
  }

  private static AttrSpec boolAttr(String key, boolean value) {
    return new AttrSpec(BOOLEAN_ATTRIBUTE, key, value);
  }

  private static AttrSpec dblAttr(String key, double value) {
    return new AttrSpec(DOUBLE_ATTRIBUTE, key, value);
  }

  // ── test cases ─────────────────────────────────────────────────────────────

  static Stream<Arguments> cases() {
    return Stream.of(
        // ── empty ─────────────────────────────────────────────────────────────
        Arguments.of("empty — no scopes produces empty payload", emptyList()),

        // ── scope with no metrics ─────────────────────────────────────────────
        Arguments.of("scope with no metrics", asList(scope("io.empty"))),

        // ── counter long boundary values ──────────────────────────────────────
        Arguments.of(
            "counter long zero — boundary", asList(scope("io.test", counterLong("requests", 0L)))),
        Arguments.of(
            "counter long Long.MAX_VALUE — boundary",
            asList(scope("io.test", counterLong("requests", Long.MAX_VALUE)))),
        Arguments.of(
            "counter long Long.MIN_VALUE — negative boundary",
            asList(scope("io.test", counterLong("requests", Long.MIN_VALUE)))),

        // ── gauge long boundary values (no start time) ───────────────────────
        Arguments.of("gauge long zero", asList(scope("io.gauge", gaugeLong("connections", 0L)))),
        Arguments.of(
            "gauge long Long.MAX_VALUE",
            asList(scope("io.gauge", gaugeLong("connections", Long.MAX_VALUE)))),
        Arguments.of(
            "gauge long Long.MIN_VALUE — negative",
            asList(scope("io.gauge", gaugeLong("balance", Long.MIN_VALUE)))),

        // ── gauge double special values (no start time) ───────────────────────
        Arguments.of("gauge double zero", asList(scope("io.gauge", gaugeDouble("rate", 0.0)))),
        Arguments.of(
            "gauge double -0.0 — negative zero",
            asList(scope("io.gauge", gaugeDouble("temperature", -0.0)))),
        Arguments.of(
            "gauge double Double.MAX_VALUE",
            asList(scope("io.gauge", gaugeDouble("temperature", Double.MAX_VALUE)))),
        Arguments.of(
            "gauge double +Infinity",
            asList(scope("io.gauge", gaugeDouble("temperature", Double.POSITIVE_INFINITY)))),
        Arguments.of(
            "gauge double -Infinity",
            asList(scope("io.gauge", gaugeDouble("temperature", Double.NEGATIVE_INFINITY)))),
        Arguments.of(
            "gauge double NaN", asList(scope("io.gauge", gaugeDouble("invalid", Double.NaN)))),

        // ── up-down counter long (non-monotonic sum) ──────────────────────────
        Arguments.of(
            "up-down-counter long zero", asList(scope("io.test", upDownLong("queue.size", 0L)))),
        Arguments.of(
            "up-down-counter long Long.MAX_VALUE",
            asList(scope("io.test", upDownLong("queue.size", Long.MAX_VALUE)))),
        Arguments.of(
            "up-down-counter long negative with string attr",
            asList(scope("io.test", upDownLong("queue.size", -42L, strAttr("host", "my-host"))))),

        // ── up-down counter double ────────────────────────────────────────────
        Arguments.of(
            "up-down-counter double positive",
            asList(scope("io.test", upDownDouble("balance", 3.14)))),
        Arguments.of(
            "up-down-counter double negative",
            asList(scope("io.test", upDownDouble("delta", -2.71)))),
        Arguments.of(
            "up-down-counter double zero", asList(scope("io.test", upDownDouble("offset", 0.0)))),

        // ── counter double ────────────────────────────────────────────────────
        Arguments.of(
            "counter double zero — no attrs",
            asList(scope("io.test", counterDouble("errors", 0.0)))),

        // ── counter double with multiple attribute types ───────────────────────
        Arguments.of(
            "counter double with string, long, bool, double attrs",
            asList(
                scope(
                    "io.test",
                    counterDouble(
                        "latency",
                        3.14,
                        strAttr("service", "web"),
                        longAttr("status", 200L),
                        boolAttr("success", true),
                        dblAttr("rate", 0.5))))),

        // ── histogram — overflow only (no explicit bounds) ────────────────────
        Arguments.of(
            "histogram no explicit bounds — overflow bucket only",
            asList(
                scope(
                    "io.hist",
                    histogram(
                        "response.time",
                        1.0,
                        asList(Double.POSITIVE_INFINITY),
                        asList(1.0),
                        0.5,
                        0.5,
                        0.5)))),

        // ── histogram — zero count and sum with overflow bucket ───────────────
        Arguments.of(
            "histogram zero count and sum",
            asList(
                scope(
                    "io.hist",
                    histogram(
                        "idle.time",
                        0.0,
                        asList(Double.POSITIVE_INFINITY),
                        asList(0.0),
                        0.0,
                        0.0,
                        0.0)))),

        // ── histogram — single explicit bound with overflow ───────────────────
        Arguments.of(
            "histogram single bound with overflow",
            asList(
                scope(
                    "io.hist",
                    histogram(
                        "request.size",
                        5.0,
                        asList(100.0, Double.POSITIVE_INFINITY),
                        asList(4.0, 1.0),
                        280.0,
                        20.0,
                        200.0)))),

        // ── histogram — finite bounds only (no overflow) — extra zero appended ─
        Arguments.of(
            "histogram finite bounds — no overflow — extra zero bucket appended",
            asList(
                scope(
                    "io.hist",
                    histogram(
                        "queue.size",
                        8.0,
                        asList(50.0, 100.0),
                        asList(3.0, 5.0),
                        750.0,
                        10.0,
                        95.0)))),

        // ── histogram — with explicit bounds, overflow, and attrs ─────────────
        Arguments.of(
            "histogram with bounds, overflow, and string attr",
            asList(
                scope(
                    "io.hist",
                    histogram(
                        "response.time",
                        10.0,
                        asList(1.0, 5.0, 10.0, Double.POSITIVE_INFINITY),
                        asList(2.0, 3.0, 4.0, 1.0),
                        45.5,
                        0.5,
                        12.0,
                        strAttr("region", "us-east"))))),

        // ── histogram — many buckets with overflow and multiple attrs ──────────
        Arguments.of(
            "histogram many buckets with overflow, long and bool attrs",
            asList(
                scope(
                    "io.hist",
                    histogram(
                        "latency.ms",
                        100.0,
                        asList(1.0, 2.0, 5.0, 10.0, 25.0, 50.0, 100.0, Double.POSITIVE_INFINITY),
                        asList(5.0, 10.0, 20.0, 30.0, 15.0, 12.0, 6.0, 2.0),
                        4321.0,
                        0.5,
                        150.0,
                        longAttr("shard", 3L),
                        boolAttr("cached", false))))),

        // ── scope metadata — optional version and schema URL ──────────────────
        Arguments.of(
            "scope with version and schemaUrl",
            asList(
                scopeFull(
                    "io.opentelemetry",
                    "1.2.3",
                    "https://opentelemetry.io/schemas/1.21",
                    counterLong("events", 1L)))),
        Arguments.of(
            "scope with version only — no schemaUrl",
            asList(scopeFull("io.versioned", "2.0.0", null, counterLong("events", 1L)))),
        Arguments.of(
            "scope with schemaUrl only — no version",
            asList(
                scopeFull(
                    "io.schemed",
                    null,
                    "https://opentelemetry.io/schemas/1.21",
                    counterLong("events", 1L)))),

        // ── metric metadata — optional description and unit ───────────────────
        Arguments.of(
            "metric with description and unit",
            asList(
                scope(
                    "io.test",
                    counterLongFull("cpu.usage", "CPU utilisation of the process", "%", 75L)))),

        // ── observable gauge ──────────────────────────────────────────────────
        Arguments.of(
            "observable gauge long — no start time written",
            asList(scope("io.obs", observableGaugeLong("heap.used", 1024L * 1024L)))),
        Arguments.of(
            "observable gauge long zero", asList(scope("io.obs", observableGaugeLong("idle", 0L)))),
        Arguments.of(
            "observable gauge long Long.MIN_VALUE — negative",
            asList(scope("io.obs", observableGaugeLong("balance", Long.MIN_VALUE)))),
        Arguments.of(
            "observable gauge double",
            asList(scope("io.obs", observableGaugeDouble("cpu.percent", 75.5)))),
        Arguments.of(
            "observable gauge double NaN",
            asList(scope("io.obs", observableGaugeDouble("invalid", Double.NaN)))),

        // ── observable counter (monotonic sum) ────────────────────────────────
        Arguments.of(
            "observable counter long Long.MAX_VALUE — has temporality and is_monotonic",
            asList(scope("io.obs", observableCounterLong("file.reads", Long.MAX_VALUE)))),
        Arguments.of(
            "observable counter long zero",
            asList(scope("io.obs", observableCounterLong("events", 0L)))),
        Arguments.of(
            "observable counter double",
            asList(scope("io.obs", observableCounterDouble("bytes.sent", 1024.5)))),
        Arguments.of(
            "observable counter double zero",
            asList(scope("io.obs", observableCounterDouble("noop", 0.0)))),

        // ── observable up-down-counter (non-monotonic sum) ────────────────────
        Arguments.of(
            "observable up-down-counter long positive",
            asList(scope("io.obs", observableUpDownCounterLong("queue.depth", 42L)))),
        Arguments.of(
            "observable up-down-counter long negative",
            asList(scope("io.obs", observableUpDownCounterLong("balance", -10L)))),
        Arguments.of(
            "observable up-down-counter long zero",
            asList(scope("io.obs", observableUpDownCounterLong("empty", 0L)))),
        Arguments.of(
            "observable up-down-counter double",
            asList(scope("io.obs", observableUpDownCounterDouble("ratio", 0.75)))),
        Arguments.of(
            "observable up-down-counter double negative",
            asList(scope("io.obs", observableUpDownCounterDouble("delta", -1.5)))),

        // ── empty scope between two scopes with metrics ───────────────────────
        Arguments.of(
            "middle scope with no metrics — flanked by scopes with metrics",
            asList(
                scope("io.first", counterLong("a", 1L)),
                scope("io.empty"),
                scope("io.last", counterLong("b", 2L)))),

        // ── multiple scopes and multiple metrics ──────────────────────────────
        Arguments.of(
            "two scopes each with two metrics",
            asList(
                scope(
                    "io.http",
                    counterLong("requests", 100L, strAttr("method", "GET")),
                    gaugeDouble("active.connections", 5.0)),
                scope(
                    "io.db",
                    counterLong("queries", 50L),
                    upDownLong("pool.size", 10L, longAttr("pool.id", 1L))))));
  }

  // ── parameterized test ────────────────────────────────────────────────────

  private static final long START_EPOCH_NS = TimeUnit.SECONDS.toNanos(1330837567);
  private static final long END_EPOCH_NS = START_EPOCH_NS + TimeUnit.MINUTES.toNanos(30);

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void testCollectMetrics(String caseName, List<ScopeSpec> expectedScopes) throws IOException {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    timeSource.set(START_EPOCH_NS); // captured in constructor
    OtlpMetricsProtoCollector collector = new OtlpMetricsProtoCollector(timeSource);
    timeSource.set(END_EPOCH_NS); // captured during collection
    OtlpPayload payload =
        collector.collectMetrics(
            visitor -> {
              for (ScopeSpec scope : expectedScopes) {
                OtlpScopedMetricsVisitor sv = visitor.visitScopedMetrics(scope.toScope());
                for (MetricSpec metric : scope.metrics) {
                  OtlpMetricVisitor mv = sv.visitMetric(metric.toDescriptor());
                  for (AttrSpec attr : metric.attrs) {
                    mv.visitAttribute(attr.type, attr.key, attr.value);
                  }
                  mv.visitDataPoint(metric.point);
                }
              }
            });

    // Scopes with no metrics produce no wire output — filter them for verification
    List<ScopeSpec> nonEmptyScopes = new ArrayList<>();
    for (ScopeSpec scope : expectedScopes) {
      if (!scope.metrics.isEmpty()) nonEmptyScopes.add(scope);
    }

    if (nonEmptyScopes.isEmpty()) {
      assertEquals(0, payload.getContentLength(), "empty registry must produce empty payload");
      return;
    }

    assertTrue(payload.getContentLength() > 0, "non-empty registry must produce bytes");

    // ── parse MetricsData ──────────────────────────────────────────────────
    // The full payload encodes a single MetricsData.resource_metrics (field 1, LEN).
    CodedInputStream metricsData = CodedInputStream.newInstance(payload.getContent());
    int metricsTag = metricsData.readTag();
    assertEquals(
        1, WireFormat.getTagFieldNumber(metricsTag), "MetricsData.resource_metrics is field 1");
    assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(metricsTag));
    CodedInputStream resourceMetrics = metricsData.readBytes().newCodedInput();
    assertTrue(metricsData.isAtEnd(), "expected exactly one ResourceMetrics");

    // ── parse ResourceMetrics (order-insensitive) ──────────────────────────
    // Fields: resource=1, scope_metrics=2 (repeated)
    boolean resourceFound = false;
    Map<String, ParsedScopeMetrics> parsedScopes = new HashMap<>();
    while (!resourceMetrics.isAtEnd()) {
      int tag = resourceMetrics.readTag();
      int rmField = WireFormat.getTagFieldNumber(tag);
      if (rmField == 1) {
        verifyResource(resourceMetrics.readBytes().newCodedInput());
        resourceFound = true;
        continue;
      }
      assertEquals(2, rmField, "ResourceMetrics.scope_metrics is field 2");
      ParsedScopeMetrics parsedScope =
          parseScopeMetrics(resourceMetrics.readBytes().newCodedInput());
      parsedScopes.put(parsedScope.name, parsedScope);
    }
    assertTrue(resourceFound, "Resource message must be present in ResourceMetrics");
    assertEquals(
        nonEmptyScopes.size(), parsedScopes.size(), "scope count mismatch in case: " + caseName);

    for (ScopeSpec expected : nonEmptyScopes) {
      ParsedScopeMetrics parsedScope = parsedScopes.get(expected.name);
      assertNotNull(
          parsedScope,
          "no ScopeMetrics found for scope '" + expected.name + "' [" + caseName + "]");
      assertEquals(expected.version, parsedScope.version, "scope version");
      assertEquals(expected.schemaUrl, parsedScope.schemaUrl, "scope schemaUrl");
      assertEquals(
          expected.metrics.size(),
          parsedScope.metricBlobs.size(),
          "metric count in scope " + expected.name);

      Map<String, MetricSpec> expectedMetricsByName = new HashMap<>();
      for (MetricSpec metricSpec : expected.metrics) {
        expectedMetricsByName.put(metricSpec.name, metricSpec);
      }
      for (byte[] metricBlob : parsedScope.metricBlobs) {
        String metricName = parseMetricName(metricBlob);
        MetricSpec metricSpec = expectedMetricsByName.get(metricName);
        assertNotNull(
            metricSpec,
            "unexpected metric '"
                + metricName
                + "' in scope "
                + expected.name
                + " ["
                + caseName
                + "]");
        verifyMetric(CodedInputStream.newInstance(metricBlob), metricSpec);
      }
    }
  }

  // ── verification helpers ──────────────────────────────────────────────────

  /**
   * Parses a {@code Resource} message body and asserts it contains a {@code service.name}
   * attribute. The value is not verified as it depends on the runtime environment.
   *
   * <pre>
   *   Resource { repeated KeyValue attributes = 1; }
   * </pre>
   */
  private static void verifyResource(CodedInputStream resource) throws IOException {
    boolean foundServiceName = false;
    while (!resource.isAtEnd()) {
      int tag = resource.readTag();
      if (WireFormat.getTagFieldNumber(tag) == 1) { // attributes (repeated KeyValue)
        String key = readKeyValueKey(resource.readBytes().newCodedInput());
        if ("service.name".equals(key)) {
          foundServiceName = true;
        }
      } else {
        resource.skipField(tag);
      }
    }
    assertTrue(foundServiceName, "Resource must contain a 'service.name' attribute");
  }

  /**
   * Parses a {@code ScopeMetrics} message body into a {@link ParsedScopeMetrics} containing the
   * scope identity and raw bytes of each {@code metrics} entry.
   *
   * <pre>
   *   ScopeMetrics { scope=1, metrics=2, schema_url=3 }
   * </pre>
   */
  private static ParsedScopeMetrics parseScopeMetrics(CodedInputStream scopeMetrics)
      throws IOException {
    String name = null;
    String version = null;
    String schemaUrl = null;
    List<byte[]> metricBlobs = new ArrayList<>();
    while (!scopeMetrics.isAtEnd()) {
      int tag = scopeMetrics.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1: // InstrumentationScope
          CodedInputStream scopeStream = scopeMetrics.readBytes().newCodedInput();
          while (!scopeStream.isAtEnd()) {
            int scopeTag = scopeStream.readTag();
            switch (WireFormat.getTagFieldNumber(scopeTag)) {
              case 1:
                name = scopeStream.readString();
                break;
              case 2:
                version = scopeStream.readString();
                break;
              default:
                scopeStream.skipField(scopeTag);
            }
          }
          break;
        case 2: // Metric (repeated)
          metricBlobs.add(scopeMetrics.readBytes().toByteArray());
          break;
        case 3: // schema_url
          schemaUrl = scopeMetrics.readString();
          break;
        default:
          scopeMetrics.skipField(tag);
      }
    }
    return new ParsedScopeMetrics(name, version, schemaUrl, metricBlobs);
  }

  private static String parseMetricName(byte[] blob) throws IOException {
    CodedInputStream metricStream = CodedInputStream.newInstance(blob);
    while (!metricStream.isAtEnd()) {
      int tag = metricStream.readTag();
      if (WireFormat.getTagFieldNumber(tag) == 1) {
        return metricStream.readString();
      }
      metricStream.skipField(tag);
    }
    return null;
  }

  /**
   * Parses a {@code Metric} message body and asserts its content matches {@code expected}.
   *
   * <pre>
   *   Metric { name=1, description=2, unit=3, gauge=5, sum=7, histogram=9 }
   * </pre>
   */
  private static void verifyMetric(CodedInputStream metric, MetricSpec expected)
      throws IOException {
    String parsedName = null;
    String parsedDesc = null;
    String parsedUnit = null;
    boolean dataFound = false;

    while (!metric.isAtEnd()) {
      int tag = metric.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1:
          parsedName = metric.readString();
          break;
        case 2:
          parsedDesc = metric.readString();
          break;
        case 3:
          parsedUnit = metric.readString();
          break;
        case 5: // Gauge
          assertTrue(isGaugeType(expected.type), "unexpected gauge for " + expected.name);
          verifyGauge(metric.readBytes().newCodedInput(), expected);
          dataFound = true;
          break;
        case 7: // Sum
          assertTrue(isSumType(expected.type), "unexpected sum for " + expected.name);
          verifySum(metric.readBytes().newCodedInput(), expected);
          dataFound = true;
          break;
        case 9: // Histogram
          assertEquals(HISTOGRAM, expected.type, "unexpected histogram for " + expected.name);
          verifyHistogram(metric.readBytes().newCodedInput(), expected);
          dataFound = true;
          break;
        default:
          metric.skipField(tag);
      }
    }

    assertEquals(expected.name, parsedName, "metric name");
    assertEquals(expected.description, parsedDesc, "metric description");
    assertEquals(expected.unit, parsedUnit, "metric unit");
    assertTrue(dataFound, "no data payload found in metric " + expected.name);
  }

  private static boolean isGaugeType(OtelInstrumentType type) {
    return type == GAUGE || type == OBSERVABLE_GAUGE;
  }

  private static boolean isSumType(OtelInstrumentType type) {
    return type == COUNTER
        || type == OBSERVABLE_COUNTER
        || type == UP_DOWN_COUNTER
        || type == OBSERVABLE_UP_DOWN_COUNTER;
  }

  /**
   * Parses a {@code Gauge} message body. Gauge has no aggregation temporality or start time.
   *
   * <pre>
   *   Gauge { data_points=1 }
   * </pre>
   */
  private static void verifyGauge(CodedInputStream gauge, MetricSpec expected) throws IOException {
    boolean foundDataPoint = false;
    while (!gauge.isAtEnd()) {
      int tag = gauge.readTag();
      if (WireFormat.getTagFieldNumber(tag) == 1) {
        assertFalse(foundDataPoint, "expected exactly one data point in gauge " + expected.name);
        verifyNumberDataPoint(
            gauge.readBytes().newCodedInput(), expected, /* hasStartTime= */ false);
        foundDataPoint = true;
      } else {
        gauge.skipField(tag);
      }
    }
    assertTrue(foundDataPoint, "no data point found in gauge " + expected.name);
  }

  /**
   * Parses a {@code Sum} message body and verifies temporality and monotonicity.
   *
   * <pre>
   *   Sum { data_points=1, aggregation_temporality=2, is_monotonic=3 }
   * </pre>
   */
  private static void verifySum(CodedInputStream sum, MetricSpec expected) throws IOException {
    boolean foundDataPoint = false;
    boolean foundTemporality = false;

    while (!sum.isAtEnd()) {
      int tag = sum.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1: // NumberDataPoint
          assertFalse(foundDataPoint, "expected exactly one data point in sum " + expected.name);
          verifyNumberDataPoint(
              sum.readBytes().newCodedInput(), expected, /* hasStartTime= */ true);
          foundDataPoint = true;
          break;
        case 2: // AggregationTemporality (1=DELTA, 2=CUMULATIVE)
          int temporality = sum.readEnum();
          assertTrue(
              temporality == 1 || temporality == 2,
              "aggregation_temporality must be DELTA(1) or CUMULATIVE(2)");
          foundTemporality = true;
          break;
        case 3: // is_monotonic
          boolean isMonotonic = sum.readBool();
          boolean expectedMonotonic =
              expected.type == COUNTER || expected.type == OBSERVABLE_COUNTER;
          assertEquals(expectedMonotonic, isMonotonic, "is_monotonic for " + expected.name);
          break;
        default:
          sum.skipField(tag);
      }
    }

    assertTrue(foundDataPoint, "no data point found in sum " + expected.name);
    assertTrue(foundTemporality, "aggregation_temporality missing from sum " + expected.name);
  }

  /**
   * Parses a {@code Histogram} message body and verifies temporality.
   *
   * <pre>
   *   Histogram { data_points=1, aggregation_temporality=2 }
   * </pre>
   */
  private static void verifyHistogram(CodedInputStream histogram, MetricSpec expected)
      throws IOException {
    boolean foundDataPoint = false;
    boolean foundTemporality = false;

    while (!histogram.isAtEnd()) {
      int tag = histogram.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1: // HistogramDataPoint
          assertFalse(
              foundDataPoint, "expected exactly one data point in histogram " + expected.name);
          verifyHistogramDataPoint(histogram.readBytes().newCodedInput(), expected);
          foundDataPoint = true;
          break;
        case 2: // AggregationTemporality
          int temporality = histogram.readEnum();
          assertTrue(
              temporality == 1 || temporality == 2,
              "aggregation_temporality must be DELTA(1) or CUMULATIVE(2)");
          foundTemporality = true;
          break;
        default:
          histogram.skipField(tag);
      }
    }

    assertTrue(foundDataPoint, "no data point found in histogram " + expected.name);
    assertTrue(foundTemporality, "aggregation_temporality missing from histogram " + expected.name);
  }

  /**
   * Parses a {@code NumberDataPoint} message body and asserts timestamps, value, and attributes.
   *
   * <pre>
   *   NumberDataPoint { start_time_unix_nano=2, time_unix_nano=3,
   *                     as_double=4, as_int=6, attributes=7 }
   * </pre>
   *
   * @param hasStartTime true for non-gauge types; gauges omit {@code start_time_unix_nano}
   */
  private static void verifyNumberDataPoint(
      CodedInputStream dataPoint, MetricSpec expected, boolean hasStartTime) throws IOException {
    boolean foundStartTime = false;
    boolean foundEndTime = false;
    boolean foundValue = false;
    List<String> parsedAttrKeys = new ArrayList<>();

    while (!dataPoint.isAtEnd()) {
      int tag = dataPoint.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 2: // start_time_unix_nano (fixed64)
          assertEquals(
              START_EPOCH_NS, dataPoint.readFixed64(), "start_time_unix_nano for " + expected.name);
          foundStartTime = true;
          break;
        case 3: // time_unix_nano (fixed64)
          assertEquals(
              END_EPOCH_NS, dataPoint.readFixed64(), "time_unix_nano for " + expected.name);
          foundEndTime = true;
          break;
        case 4: // as_double (double via fixed64 wire type)
          double parsedDouble = dataPoint.readDouble();
          OtlpDoublePoint expectedDouble = (OtlpDoublePoint) expected.point;
          assertEquals(
              Double.doubleToRawLongBits(expectedDouble.value),
              Double.doubleToRawLongBits(parsedDouble),
              "as_double for " + expected.name);
          foundValue = true;
          break;
        case 6: // as_int (sfixed64)
          long parsedLong = dataPoint.readSFixed64();
          OtlpLongPoint expectedLong = (OtlpLongPoint) expected.point;
          assertEquals(expectedLong.value, parsedLong, "as_int for " + expected.name);
          foundValue = true;
          break;
        case 7: // attributes (repeated KeyValue)
          parsedAttrKeys.add(readKeyValueKey(dataPoint.readBytes().newCodedInput()));
          break;
        default:
          dataPoint.skipField(tag);
      }
    }

    assertEquals(
        hasStartTime, foundStartTime, "start_time_unix_nano presence for " + expected.name);
    assertTrue(foundEndTime, "time_unix_nano required for " + expected.name);
    assertTrue(foundValue, "value field required for " + expected.name);
    assertEquals(
        expected.attrs.size(), parsedAttrKeys.size(), "attribute count for " + expected.name);
    for (int i = 0; i < expected.attrs.size(); i++) {
      assertEquals(
          expected.attrs.get(i).key,
          parsedAttrKeys.get(i),
          "attribute key[" + i + "] for " + expected.name);
    }
  }

  /**
   * Parses a {@code HistogramDataPoint} message body and asserts timestamps, count, sum, min, max,
   * bucket counts, explicit bounds, and attributes.
   *
   * <pre>
   *   HistogramDataPoint { start_time_unix_nano=2, time_unix_nano=3, count=4,
   *                        sum=5, bucket_counts=6, explicit_bounds=7, attributes=9,
   *                        min=11, max=12 }
   * </pre>
   */
  private static void verifyHistogramDataPoint(CodedInputStream dataPoint, MetricSpec expected)
      throws IOException {
    OtlpHistogramPoint hp = (OtlpHistogramPoint) expected.point;
    boolean foundStartTime = false;
    boolean foundEndTime = false;
    boolean foundCount = false;
    boolean foundSum = false;
    boolean foundMin = false;
    boolean foundMax = false;
    List<Long> parsedBucketCounts = new ArrayList<>();
    List<Double> parsedBounds = new ArrayList<>();
    List<String> parsedAttrKeys = new ArrayList<>();

    while (!dataPoint.isAtEnd()) {
      int tag = dataPoint.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 2: // start_time_unix_nano
          assertEquals(
              START_EPOCH_NS, dataPoint.readFixed64(), "start_time_unix_nano for " + expected.name);
          foundStartTime = true;
          break;
        case 3: // time_unix_nano
          assertEquals(
              END_EPOCH_NS, dataPoint.readFixed64(), "time_unix_nano for " + expected.name);
          foundEndTime = true;
          break;
        case 4: // count (fixed64)
          assertEquals((long) hp.count, dataPoint.readFixed64(), "histogram count");
          foundCount = true;
          break;
        case 5: // sum (double via fixed64)
          assertEquals(
              Double.doubleToRawLongBits(hp.sum),
              Double.doubleToRawLongBits(dataPoint.readDouble()),
              "histogram sum");
          foundSum = true;
          break;
        case 6: // bucket_counts (repeated fixed64)
          parsedBucketCounts.add(dataPoint.readFixed64());
          break;
        case 7: // explicit_bounds (repeated double)
          parsedBounds.add(dataPoint.readDouble());
          break;
        case 9: // attributes (repeated KeyValue)
          parsedAttrKeys.add(readKeyValueKey(dataPoint.readBytes().newCodedInput()));
          break;
        case 11: // min (double via fixed64)
          assertEquals(
              Double.doubleToRawLongBits(hp.min),
              Double.doubleToRawLongBits(dataPoint.readDouble()),
              "histogram min");
          foundMin = true;
          break;
        case 12: // max (double via fixed64)
          assertEquals(
              Double.doubleToRawLongBits(hp.max),
              Double.doubleToRawLongBits(dataPoint.readDouble()),
              "histogram max");
          foundMax = true;
          break;
        default:
          dataPoint.skipField(tag);
      }
    }

    assertTrue(foundStartTime, "start_time_unix_nano required for histogram " + expected.name);
    assertTrue(foundEndTime, "time_unix_nano required for histogram " + expected.name);
    assertTrue(foundCount, "count required for histogram " + expected.name);
    assertTrue(foundSum, "sum required for histogram " + expected.name);
    assertTrue(foundMin, "min required for histogram " + expected.name);
    assertTrue(foundMax, "max required for histogram " + expected.name);

    // Input uses equal counts and boundaries; derive the expected OTLP wire output:
    // - finite boundaries are written as explicit_bounds (+Infinity overflow marker is dropped)
    // - when there is no overflow boundary, one extra zero count is appended so that
    //   bucket_counts.size() == explicit_bounds.size() + 1, as required by the OTLP spec
    List<Double> expectedBounds = new ArrayList<>();
    boolean hasOverflow = false;
    for (double boundary : hp.bucketBoundaries) {
      if (Double.isInfinite(boundary)) {
        hasOverflow = true;
      } else {
        expectedBounds.add(boundary);
      }
    }
    List<Long> expectedCounts = new ArrayList<>();
    for (double count : hp.bucketCounts) {
      expectedCounts.add((long) count);
    }
    if (!expectedCounts.isEmpty() && !hasOverflow) {
      expectedCounts.add(0L);
    }

    // OTLP spec: bucket_counts.size() == explicit_bounds.size() + 1, or both 0
    if (expectedCounts.isEmpty()) {
      assertEquals(
          0,
          parsedBounds.size(),
          "explicit_bounds must be 0 when bucket_counts is 0 for " + expected.name);
      assertEquals(
          0,
          parsedBucketCounts.size(),
          "bucket_counts must be 0 when explicit_bounds is 0 for " + expected.name);
    } else {
      assertEquals(
          parsedBounds.size() + 1,
          parsedBucketCounts.size(),
          "OTLP spec: bucket_counts must be explicit_bounds + 1 for " + expected.name);
    }

    // +Infinity must never appear as an explicit bound on the wire
    assertFalse(
        parsedBounds.stream().anyMatch(b -> Double.isInfinite(b)),
        "+Infinity must not appear in explicit_bounds for " + expected.name);

    assertEquals(
        expectedBounds.size(), parsedBounds.size(), "explicit_bounds size for " + expected.name);
    for (int i = 0; i < expectedBounds.size(); i++) {
      assertEquals(
          Double.doubleToRawLongBits(expectedBounds.get(i)),
          Double.doubleToRawLongBits(parsedBounds.get(i)),
          "explicit_bounds[" + i + "] for " + expected.name);
    }

    assertEquals(
        expectedCounts.size(),
        parsedBucketCounts.size(),
        "bucket_counts size for " + expected.name);
    for (int i = 0; i < expectedCounts.size(); i++) {
      assertEquals(
          (long) expectedCounts.get(i),
          (long) parsedBucketCounts.get(i),
          "bucket_counts[" + i + "] for " + expected.name);
    }

    assertEquals(
        expected.attrs.size(),
        parsedAttrKeys.size(),
        "attribute count for histogram " + expected.name);
    for (int i = 0; i < expected.attrs.size(); i++) {
      assertEquals(
          expected.attrs.get(i).key,
          parsedAttrKeys.get(i),
          "attribute key[" + i + "] for histogram " + expected.name);
    }
  }

  /**
   * Reads a {@code KeyValue} body and returns the key (field 1). The value field is skipped; its
   * encoding is covered by {@code OtlpCommonProtoTest}.
   */
  private static String readKeyValueKey(CodedInputStream keyValue) throws IOException {
    while (!keyValue.isAtEnd()) {
      int tag = keyValue.readTag();
      if (WireFormat.getTagFieldNumber(tag) == 1) {
        return keyValue.readString();
      }
      keyValue.skipField(tag);
    }
    return null;
  }

  static OtlpLongPoint longPoint(long value) {
    return new OtlpLongPoint(value);
  }

  static OtlpDoublePoint doublePoint(double value) {
    return new OtlpDoublePoint(value);
  }

  static OtlpHistogramPoint histogramPoint(
      double count,
      List<Double> bucketBoundaries,
      List<Double> bucketCounts,
      double sum,
      double min,
      double max) {
    return new OtlpHistogramPoint(count, bucketBoundaries, bucketCounts, sum, min, max);
  }
}
