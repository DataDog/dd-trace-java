package com.datadog.featureflag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import datadog.trace.api.telemetry.CoreMetricCollector;
import datadog.trace.api.telemetry.MetricCollector;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.RequestBody;
import okio.Buffer;

final class FlagEvaluationTestSupport {

  static final long REALISTIC_EVAL_MS = 1_760_000_000_000L;
  static final JsonAdapter<Map<String, Object>> JSON_MAP;

  static {
    final Moshi moshi = new Moshi.Builder().build();
    final Type type = Types.newParameterizedType(Map.class, String.class, Object.class);
    JSON_MAP = moshi.adapter(type);
  }

  private FlagEvaluationTestSupport() {}

  static void clearCoreMetrics() {
    CoreMetricCollector.getInstance().drain();
  }

  static FlagEvalEvent event(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String targetingKey,
      final long evalTimeMs,
      final Map<String, Object> attrs) {
    return new FlagEvalEvent(flagKey, variant, allocationKey, targetingKey, evalTimeMs, attrs);
  }

  static FlagEvalEvent errorEvent(
      final String flagKey, final String errorMessage, final long evalTimeMs) {
    return new FlagEvalEvent(
        flagKey, null, null, null, errorMessage, evalTimeMs, java.util.Collections.emptyMap());
  }

  static FlagEvalEvent simpleEvent(final String flagKey, final String variant) {
    return event(flagKey, variant, "alloc1", "user-1", 1000L, java.util.Collections.emptyMap());
  }

  static String repeat(final char c, final int count) {
    final char[] chars = new char[count];
    Arrays.fill(chars, c);
    return new String(chars);
  }

  static TestWriterSetup buildTestWriter(final BackendApi mockEvp) {
    return buildTestWriter(
        mockEvp, FlagEvaluationWriterImpl.FLAG_EVALUATION_PAYLOAD_SIZE_LIMIT_BYTES);
  }

  static TestWriterSetup buildTestWriter(
      final BackendApi mockEvp, final int payloadSizeLimitBytes) {
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(any())).thenReturn(mockEvp);
    when(factory.createBackendApi(any(), any(), eq(false))).thenReturn(mockEvp);

    final Map<String, String> context = new HashMap<>();
    context.put("service", "test-service");

    final FlagEvaluationWriterImpl.SerializingHandlerForTest handler =
        FlagEvaluationWriterImpl.createHandlerForTest(factory, context, payloadSizeLimitBytes);

    return new TestWriterSetup(handler, mockEvp, factory);
  }

  static CapturedJson flushAndCapture(final TestWriterSetup setup) throws Exception {
    final List<CapturedJson> captured = flushAndCaptureAll(setup);
    assertEquals(1, captured.size(), "Expected exactly one posted payload");
    return captured.get(0);
  }

  static List<CapturedJson> flushAndCaptureAll(final TestWriterSetup setup) throws Exception {
    final List<RequestBody> captured = new ArrayList<>();
    when(setup.mockEvp.post(eq("flagevaluation"), any(RequestBody.class), any(), any(), eq(false)))
        .thenAnswer(
            inv -> {
              captured.add(inv.getArgument(1));
              return null;
            });
    setup.handler.drainAndAggregate();
    setup.handler.flush();
    final List<CapturedJson> json = new ArrayList<>();
    for (final RequestBody body : captured) {
      json.add(readJson(body));
    }
    return json;
  }

  static CapturedJson readJson(final RequestBody body) throws Exception {
    assertNotNull(body, "RequestBody must have been posted");
    final Buffer buf = new Buffer();
    body.writeTo(buf);
    final String raw = buf.readUtf8();
    return new CapturedJson(raw, JSON_MAP.fromJson(raw));
  }

  static Map<String, Object> flushAndCaptureJson(final TestWriterSetup setup) throws Exception {
    return flushAndCapture(setup).parsed;
  }

  static long metricSum(
      final Collection<? extends MetricCollector.Metric> metrics,
      final String metricName,
      final String tag) {
    long sum = 0;
    for (final MetricCollector.Metric metric : metrics) {
      if (!metricName.equals(metric.metricName)) {
        continue;
      }
      if (tag == null) {
        if (!metric.tags.isEmpty()) {
          continue;
        }
      } else if (!metric.tags.contains(tag)) {
        continue;
      }
      sum += metric.value.longValue();
    }
    return sum;
  }

  static datadog.trace.api.Config cfg() {
    final datadog.trace.api.Config config = mock(datadog.trace.api.Config.class);
    when(config.getServiceName()).thenReturn("test-service");
    return config;
  }

  static void assertObjectWithKey(final Object o, final String expectedKey, final String msg) {
    assertTrue(o instanceof Map, msg + " (must be a JSON object, not a bare string)");
    assertEquals(expectedKey, ((Map<?, ?>) o).get("key"), msg);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> firstEvent(final Map<String, Object> batch) {
    final List<Object> events = (List<Object>) batch.get("flagEvaluations");
    assertNotNull(events, "flagEvaluations array must be present");
    assertFalse(events.isEmpty(), "flagEvaluations must not be empty");
    return (Map<String, Object>) events.get(0);
  }

  @SuppressWarnings("unchecked")
  static int eventCount(final Map<String, Object> batch) {
    final List<Object> events = (List<Object>) batch.get("flagEvaluations");
    assertNotNull(events, "flagEvaluations array must be present");
    return events.size();
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> eventForFlag(final Map<String, Object> batch, final String flagKey) {
    final List<Object> events = (List<Object>) batch.get("flagEvaluations");
    for (final Object o : events) {
      final Map<String, Object> ev = (Map<String, Object>) o;
      final Map<?, ?> flag = (Map<?, ?>) ev.get("flag");
      if (flag != null && flagKey.equals(flag.get("key"))) {
        return ev;
      }
    }
    return null;
  }

  static class TestWriterSetup {
    final FlagEvaluationWriterImpl.SerializingHandlerForTest handler;
    final BackendApi mockEvp;
    final BackendApiFactory factory;

    TestWriterSetup(
        final FlagEvaluationWriterImpl.SerializingHandlerForTest handler,
        final BackendApi mockEvp,
        final BackendApiFactory factory) {
      this.handler = handler;
      this.mockEvp = mockEvp;
      this.factory = factory;
    }
  }

  static class CapturedJson {
    final String raw;
    final Map<String, Object> parsed;

    CapturedJson(final String raw, final Map<String, Object> parsed) {
      this.raw = raw;
      this.parsed = parsed;
    }
  }
}
