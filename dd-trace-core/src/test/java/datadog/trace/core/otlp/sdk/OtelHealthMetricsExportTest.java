package datadog.trace.core.otlp.sdk;

import static datadog.trace.agent.test.utils.OtelMetricCollector.collect;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.COUNTER;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.HISTOGRAM;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.UP_DOWN_COUNTER;
import static datadog.trace.common.writer.RemoteApi.Response.failed;
import static datadog.trace.common.writer.RemoteApi.Response.success;
import static datadog.trace.core.monitor.ExporterId.Signal.LOG;
import static datadog.trace.core.monitor.ExporterId.Signal.METRIC_DATA_POINT;
import static datadog.trace.core.monitor.ExporterId.Signal.SPAN;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.metrics.api.Histograms;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.trace.agent.test.utils.OtelMetricCollector;
import datadog.trace.agent.test.utils.OtelMetricCollector.DataPointEntry;
import datadog.trace.bootstrap.otlp.metrics.OtlpHistogramPoint;
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.core.monitor.ComponentId;
import datadog.trace.core.monitor.ExporterId;
import datadog.trace.core.otlp.common.OtlpGrpcStatusException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class OtelHealthMetricsExportTest {
  private static final String DURATION = "otel.sdk.exporter.operation.duration";
  private static final String INFLIGHT = "otel.sdk.exporter.span.inflight";
  private static final String EXPORTED = "otel.sdk.exporter.span.exported";
  private static final HttpUrl DESTINATION = HttpUrl.get("http://collector:4318/v1/traces");
  private static final OtelHealthMetrics SINK = OtelHealthMetrics.INSTANCE;

  @BeforeAll
  static void registerHistogramFactory() {
    Histograms.register(DDSketchHistograms.FACTORY);
  }

  @Test
  void tracksInflightExportedAndDuration() {
    ExporterId id = exporter(SPAN, "otel_export_test_http", DESTINATION, false);

    SINK.onExportStart(id, 3);
    SINK.onExportStart(id, 2);
    assertEquals(5L, sum(collect(), INFLIGHT, id));

    end(id, 3, success(200), null);
    end(id, 2, failed(503), null);
    OtelMetricCollector collector = collect();

    assertEquals(0L, sum(collector, INFLIGHT, id));
    List<DataPointEntry> exported = points(collector, EXPORTED, id);
    assertEquals(2, exported.size());
    for (DataPointEntry point : exported) {
      assertEquals("otel_export_test_http", point.attrs.get("otel.component.type"));
      assertEquals("collector", point.attrs.get("server.address"));
      assertEquals("4318", point.attrs.get("server.port"));
    }
    assertEquals(byErrorType(null, 3L, "503", 2L), valuesByErrorType(exported));

    List<DataPointEntry> durations = points(collector, DURATION, id);
    assertEquals(2, durations.size());
    for (DataPointEntry point : durations) {
      OtlpHistogramPoint histogram = (OtlpHistogramPoint) point.point;
      assertEquals(1.0, histogram.count);
      assertEquals(1, histogram.bucketCounts.size()); // empty bucket advice: a single bucket
      assertEquals(0.25, histogram.sum); // 250 ms in seconds
      Object errorType = point.attrs.get("error.type");
      assertEquals(errorType == null ? "200" : "503", point.attrs.get("http.response.status_code"));
      assertFalse(point.attrs.containsKey("rpc.status_code"));
    }
  }

  @Test
  void mapsExceptionsAndMissingResponsesToErrorTypes() {
    ExporterId id = exporter(SPAN, "otel_export_test_exception", null, false);

    end(id, 4, null, new SocketTimeoutException());
    end(id, 1, failed(new IOException()), null);
    end(id, 6, null, null);
    end(id, 7, failed(0), null); // a failure with neither status nor exception
    OtelMetricCollector collector = collect();

    List<DataPointEntry> durations = points(collector, DURATION, id);
    assertEquals(3, durations.size()); // timeout, java.io.IOException and _OTHER
    for (DataPointEntry point : durations) {
      assertFalse(point.attrs.containsKey("http.response.status_code"));
      assertFalse(point.attrs.containsKey("server.address"));
      assertFalse(point.attrs.containsKey("server.port"));
    }
    Map<String, Long> expected = byErrorType("timeout", 4L, "java.io.IOException", 1L);
    expected.put("_OTHER", 13L);
    assertEquals(expected, valuesByErrorType(points(collector, EXPORTED, id)));
  }

  @Test
  void grpcExportsReportTheGrpcStatus() {
    ExporterId id =
        exporter(
            METRIC_DATA_POINT,
            "otel_export_test_grpc",
            HttpUrl.get("http://collector:4317/x"),
            true);

    end(id, 2, success(200), null);
    end(id, 5, failed(new OtlpGrpcStatusException(14, "gRPC status 14: unavailable")), null);
    end(id, 1, failed(502), null); // HTTP failure before any gRPC status
    OtelMetricCollector collector = collect();

    Map<String, Long> expected = byErrorType(null, 2L, "UNAVAILABLE", 5L);
    expected.put("502", 1L);
    assertEquals(
        expected,
        valuesByErrorType(points(collector, "otel.sdk.exporter.metric_data_point.exported", id)));
    Map<Object, Object> rpcStatusByErrorType = new HashMap<>();
    for (DataPointEntry point : points(collector, DURATION, id)) {
      assertFalse(point.attrs.containsKey("http.response.status_code"));
      assertEquals("4317", point.attrs.get("server.port"));
      rpcStatusByErrorType.put(point.attrs.get("error.type"), point.attrs.get("rpc.status_code"));
    }
    Map<Object, Object> expectedStatus = new HashMap<>();
    expectedStatus.put(null, "OK");
    expectedStatus.put("UNAVAILABLE", "UNAVAILABLE");
    expectedStatus.put("502", null);
    assertEquals(expectedStatus, rpcStatusByErrorType);
  }

  @Test
  void namesInstrumentsInSdkScope() {
    ExporterId id = exporter(LOG, "otel_export_test_names", DESTINATION, false);
    SINK.onExportStart(id, 1);
    end(id, 1, success(200), null);
    OtelMetricCollector collector = collect();

    String prefix = "otel.sdk.exporter.log";
    assertEquals(UP_DOWN_COUNTER, collector.descriptors.get(prefix + ".inflight").getType());
    assertEquals(COUNTER, collector.descriptors.get(prefix + ".exported").getType());
    assertEquals(HISTOGRAM, collector.descriptors.get(DURATION).getType());
    assertEquals(
        "{log_record}", collector.descriptors.get(prefix + ".exported").getUnit().toString());
    assertEquals("s", collector.descriptors.get(DURATION).getUnit().toString());
    assertEquals("datadog.trace.sdk", collector.scopes.get(prefix + ".exported"));
    assertEquals(1L, sum(collector, prefix + ".exported", id));
    assertTrue(collector.metricNames.contains("otel.sdk.exporter.span.exported"));
  }

  @TableTest({
    "scenario    | error                           | errorType                      ",
    "timeout     | java.net.SocketTimeoutException | timeout                        ",
    "interrupted | java.io.InterruptedIOException  | java.io.InterruptedIOException ",
    "other I/O   | java.net.ConnectException       | java.net.ConnectException      ",
    "runtime     | java.lang.IllegalStateException | java.lang.IllegalStateException"
  })
  void mapsExceptionClassesToErrorTypes(String error, String errorType) throws Exception {
    Throwable throwable = (Throwable) Class.forName(error).getConstructor().newInstance();
    assertEquals(errorType, ErrorTypes.fromException(throwable));
  }

  @Test
  void mapsOkHttpCallTimeoutsToTimeout() {
    assertEquals("timeout", ErrorTypes.fromException(new InterruptedIOException("timeout")));
    assertEquals(
        "java.io.InterruptedIOException",
        ErrorTypes.fromException(new InterruptedIOException("interrupted")));
  }

  @TableTest({
    "code | name       ",
    "14   | UNAVAILABLE",
    "2    | UNKNOWN    ",
    "42   | 42         "
  })
  void grpcStatusFailuresUseTheStatusName(int code, String name) {
    ExporterId id = exporter(SPAN, "otel_export_test_grpc_" + code, null, true);
    end(id, 1, failed(new OtlpGrpcStatusException(code, "x")), null);

    DataPointEntry duration = points(collect(), DURATION, id).get(0);
    assertEquals(name, duration.attrs.get("error.type"));
    assertEquals(name, duration.attrs.get("rpc.status_code"));
  }

  private static ExporterId exporter(
      ExporterId.Signal signal, String type, HttpUrl destination, boolean grpc) {
    return new ExporterId(signal, ComponentId.next(type), destination, grpc);
  }

  /** Ends an export that took 250 ms. */
  private static void end(ExporterId id, long items, RemoteApi.Response response, Throwable error) {
    SINK.onExportEnd(id, items, 250_000_000L, response, error);
  }

  private static Map<String, Long> byErrorType(
      String type1, long value1, String type2, long value2) {
    Map<String, Long> values = new HashMap<>();
    values.put(type1, value1);
    values.put(type2, value2);
    return values;
  }

  /** Sums {@code exported} by {@code error.type}, as an exporter may end several sends alike. */
  private static Map<String, Long> valuesByErrorType(List<DataPointEntry> points) {
    Map<String, Long> values = new HashMap<>();
    for (DataPointEntry point : points) {
      values.merge((String) point.attrs.get("error.type"), point.value.longValue(), Long::sum);
    }
    return values;
  }

  private static List<DataPointEntry> points(
      OtelMetricCollector collector, String metric, ExporterId id) {
    return collector.points(metric).stream()
        .filter(point -> id.component().name().equals(point.attrs.get("otel.component.name")))
        .collect(toList());
  }

  private static long sum(OtelMetricCollector collector, String metric, ExporterId id) {
    return points(collector, metric, id).stream().mapToLong(point -> point.value.longValue()).sum();
  }
}
