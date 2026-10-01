package com.datadog.openfeature.internal.flagevaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.JsonReading;
import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.connector.EventTransport;
import com.datadog.openfeature.internal.connector.HealthMetrics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntPredicate;

final class FlagEvaluationTestSupport {

  static final long REALISTIC_EVAL_MS = 1_760_000_000_000L;

  private FlagEvaluationTestSupport() {}

  static FlagEvalEvent event(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String targetingKey,
      final long evalTimeMs,
      final Map<String, Object> attrs) {
    return new FlagEvalEvent(flagKey, variant, allocationKey, targetingKey, evalTimeMs, attrs);
  }

  static FlagEvalEvent event(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String targetingKey,
      final long evalTimeMs,
      final boolean observeFullEvaluationData,
      final Map<String, Object> attrs) {
    return new FlagEvalEvent(
        flagKey,
        variant,
        allocationKey,
        targetingKey,
        null,
        evalTimeMs,
        observeFullEvaluationData,
        attrs);
  }

  static FlagEvalEvent errorEvent(
      final String flagKey, final String errorMessage, final long evalTimeMs) {
    return new FlagEvalEvent(
        flagKey, null, null, null, errorMessage, evalTimeMs, Collections.emptyMap());
  }

  static FlagEvalEvent simpleEvent(final String flagKey, final String variant) {
    return event(flagKey, variant, "alloc1", "user-1", 1000L, Collections.emptyMap());
  }

  static String repeat(final char c, final int count) {
    final char[] chars = new char[count];
    Arrays.fill(chars, c);
    return new String(chars);
  }

  static Map<String, String> context() {
    final Map<String, String> context = new HashMap<>();
    context.put("service", "test-service");
    return context;
  }

  static TestWriterSetup buildTestWriter() {
    return buildTestWriter(FlagEvaluationPipeline.FLAG_EVALUATION_PAYLOAD_SIZE_LIMIT_BYTES);
  }

  static TestWriterSetup buildTestWriter(final int payloadSizeLimitBytes) {
    final CapturingTransport transport = new CapturingTransport();
    final RecordingMetrics metrics = new RecordingMetrics();
    final FlagEvaluationPipeline.SerializingHandlerForTest handler =
        new FlagEvaluationPipeline.SerializingHandlerForTest(
            transport, context(), payloadSizeLimitBytes, new RuntimeServices(metrics));
    return new TestWriterSetup(handler, transport, metrics);
  }

  static CapturedJson flushAndCapture(final TestWriterSetup setup) {
    final List<CapturedJson> captured = flushAndCaptureAll(setup);
    assertEquals(1, captured.size(), "Expected exactly one posted payload");
    return captured.get(0);
  }

  static List<CapturedJson> flushAndCaptureAll(final TestWriterSetup setup) {
    setup.transport.posts.clear();
    setup.handler.drainAndAggregate();
    setup.handler.flush();
    final List<CapturedJson> json = new ArrayList<>();
    for (final Post post : setup.transport.posts) {
      assertEquals("flagevaluation", post.route);
      json.add(readJson(post.body));
    }
    return json;
  }

  static CapturedJson readJson(final byte[] body) {
    assertNotNull(body, "payload must have been posted");
    return new CapturedJson(
        new String(body, StandardCharsets.UTF_8), JsonReading.readObjectWithDoubles(body));
  }

  static Map<String, Object> flushAndCaptureJson(final TestWriterSetup setup) {
    return flushAndCapture(setup).parsed;
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

  /** Records the posted payloads, optionally failing some posts. */
  static final class CapturingTransport implements EventTransport {
    final List<Post> posts = new CopyOnWriteArrayList<>();
    volatile IntPredicate failingPost = index -> false;
    volatile Runnable onPost = () -> {};

    @Override
    public void post(final String route, final byte[] json) throws IOException {
      this.posts.add(new Post(route, json));
      this.onPost.run();
      if (this.failingPost.test(this.posts.size())) {
        throw new IOException("boom");
      }
    }
  }

  static final class Post {
    final String route;
    final byte[] body;

    Post(final String route, final byte[] body) {
      this.route = route;
      this.body = body;
    }
  }

  /** Records health counters, keyed by their {@code reason:} tag like the agent collector. */
  static final class RecordingMetrics implements HealthMetrics {
    private final List<String[]> counts = new CopyOnWriteArrayList<>();

    @Override
    public void count(final String metric, final long value, final String reason) {
      this.counts.add(
          new String[] {metric, Long.toString(value), reason == null ? null : "reason:" + reason});
    }

    long sum(final String metricName, final String tag) {
      long sum = 0;
      for (final String[] count : this.counts) {
        if (metricName.equals(count[0])
            && (tag == null ? count[2] == null : tag.equals(count[2]))) {
          sum += Long.parseLong(count[1]);
        }
      }
      return sum;
    }
  }

  static class TestWriterSetup {
    final FlagEvaluationPipeline.SerializingHandlerForTest handler;
    final CapturingTransport transport;
    final RecordingMetrics metrics;

    TestWriterSetup(
        final FlagEvaluationPipeline.SerializingHandlerForTest handler,
        final CapturingTransport transport,
        final RecordingMetrics metrics) {
      this.handler = handler;
      this.transport = transport;
      this.metrics = metrics;
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
