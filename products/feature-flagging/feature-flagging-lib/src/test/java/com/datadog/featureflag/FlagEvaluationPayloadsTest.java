package com.datadog.featureflag;

import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlagEvaluationPayloadsTest {

  private static final long EVAL_MS = 1_760_000_000_000L;
  private static final Map<String, String> CONTEXT = context();
  private static final JsonAdapter<Map<String, Object>> JSON_MAP;

  static {
    final Moshi moshi = new Moshi.Builder().build();
    final Type type = Types.newParameterizedType(Map.class, String.class, Object.class);
    JSON_MAP = moshi.adapter(type);
  }

  @Test
  void fullTierPayloadUsesWorkerWireShape() throws Exception {
    final Map<String, Object> attrs = new HashMap<>();
    attrs.put("region", "us-east-1");

    final Map<String, Object> json =
        firstPayload(
            FlagEvaluationPayloads.buildPayloads(
                java.util.Collections.singletonList(
                    event("my-flag", "on", "alloc-x", "user-1", 1, attrs)),
                CONTEXT,
                1_000_000));

    final Map<String, Object> ev = firstEvent(json);
    assertObjectWithKey(ev.get("variant"), "on");
    assertObjectWithKey(ev.get("allocation"), "alloc-x");
    assertObjectWithKey(ev.get("flag"), "my-flag");
    final Map<?, ?> ctx = (Map<?, ?>) ev.get("context");
    assertNotNull(ctx);
    final Map<?, ?> evalAttrs = (Map<?, ?>) ctx.get("evaluation");
    assertNotNull(evalAttrs);
    assertEquals("us-east-1", evalAttrs.get("region"));
    assertFalse(ev.containsKey("reason"));
  }

  @Test
  void eventFromFullBucketUsesFlushTimeAndEvaluationBounds() throws Exception {
    final FlagEvaluationAggregator.EvalBucket bucket =
        new FlagEvaluationAggregator.EvalBucket(
            "ts-flag", "on", "alloc1", "user-1", null, EVAL_MS, false, emptyMap());
    bucket.merge(EVAL_MS + 10, false);
    final long flushTimeMs = EVAL_MS + 5_000;

    final Map<String, Object> json =
        firstPayload(
            FlagEvaluationPayloads.buildPayloads(
                java.util.Collections.singletonList(
                    FlagEvaluationPayloads.FlagEvaluationEvent.fromBucket(
                        bucket, true, flushTimeMs)),
                CONTEXT,
                1_000_000));

    final Map<String, Object> ev = firstEvent(json);
    assertEquals((double) flushTimeMs, ((Number) ev.get("timestamp")).doubleValue());
    assertEquals((double) EVAL_MS, ((Number) ev.get("first_evaluation")).doubleValue());
    assertEquals((double) (EVAL_MS + 10), ((Number) ev.get("last_evaluation")).doubleValue());
    assertEquals(2.0, ((Number) ev.get("evaluation_count")).doubleValue());
  }

  @Test
  void degradedTierEventOmitsTargetingKeyAndContext() throws Exception {
    final FlagEvaluationAggregator.EvalBucket bucket =
        new FlagEvaluationAggregator.EvalBucket(
            "dg-flag", "on", "alloc1", null, null, EVAL_MS, false, null);

    final Map<String, Object> json =
        firstPayload(
            FlagEvaluationPayloads.buildPayloads(
                java.util.Collections.singletonList(
                    FlagEvaluationPayloads.FlagEvaluationEvent.fromBucket(bucket, false, EVAL_MS)),
                CONTEXT,
                1_000_000));

    final Map<String, Object> ev = firstEvent(json);
    assertNull(ev.get("targeting_key"));
    assertNull(ev.get("context"));
  }

  @Test
  void splitPayloadsByEncodedSize() throws Exception {
    final Map<String, Object> attrs = new HashMap<>();
    attrs.put("payload", repeat('x', 180));
    final java.util.ArrayList<FlagEvaluationPayloads.FlagEvaluationEvent> events =
        new java.util.ArrayList<>();
    for (int i = 0; i < 4; i++) {
      events.add(event("split-flag-" + i, "on", "alloc1", "user-" + i, 1, attrs));
    }

    final FlagEvaluationPayloads.EncodedPayloads payloads =
        FlagEvaluationPayloads.buildPayloads(events, CONTEXT, 1_100);

    assertTrue(payloads.bodies.size() > 1);
    int eventCount = 0;
    for (final byte[] body : payloads.bodies) {
      assertTrue(body.length <= 1_100);
      eventCount += eventCount(parse(body));
    }
    assertEquals(4, eventCount);
    assertEquals(0, payloads.droppedPayloadLimit);
    assertEquals(0, payloads.degradedPayloadLimit);
  }

  @Test
  void oversizedFullPayloadRowIsDegradedBeforeDrop() throws Exception {
    final Map<String, Object> attrs = new HashMap<>();
    for (int i = 0; i < 4; i++) {
      attrs.put("payload-" + i, repeat('x', 200));
    }

    final FlagEvaluationPayloads.EncodedPayloads payloads =
        FlagEvaluationPayloads.buildPayloads(
            java.util.Collections.singletonList(
                event("oversized-full", "on", "alloc1", "user-1", 2, attrs)),
            CONTEXT,
            512);

    assertEquals(1, payloads.bodies.size());
    assertTrue(payloads.bodies.get(0).length <= 512);
    assertEquals(0, payloads.droppedPayloadLimit);
    assertEquals(2, payloads.degradedPayloadLimit);
    final Map<String, Object> ev = firstEvent(parse(payloads.bodies.get(0)));
    assertEquals(2.0, ((Number) ev.get("evaluation_count")).doubleValue());
    assertNull(ev.get("targeting_key"));
    assertNull(ev.get("context"));
  }

  @Test
  void oversizedFullPayloadRowStartsNewPayloadWhenDegradedRowFitsByItself() throws Exception {
    final FlagEvaluationPayloads.FlagEvaluationEvent first =
        event("first-flag", "on", "alloc1", "user-1", 1, emptyMap());
    final Map<String, Object> attrs = new HashMap<>();
    attrs.put("payload", repeat('x', 400));
    final FlagEvaluationPayloads.FlagEvaluationEvent second =
        event("second-flag", "on", "alloc1", "user-2", 3, attrs);
    final FlagEvaluationPayloads.FlagEvaluationEvent degradedSecond =
        second.withoutTargetingKeyAndContext();
    assertNotNull(degradedSecond);

    final int firstPayloadSize =
        FlagEvaluationPayloads.buildPayloads(
                java.util.Collections.singletonList(first), CONTEXT, 1_000_000)
            .bodies
            .get(0)
            .length;
    final int degradedPayloadSize =
        FlagEvaluationPayloads.buildPayloads(
                java.util.Collections.singletonList(degradedSecond), CONTEXT, 1_000_000)
            .bodies
            .get(0)
            .length;
    final int limit = Math.max(firstPayloadSize, degradedPayloadSize);

    final FlagEvaluationPayloads.EncodedPayloads payloads =
        FlagEvaluationPayloads.buildPayloads(Arrays.asList(first, second), CONTEXT, limit);

    assertEquals(2, payloads.bodies.size());
    assertEquals(0, payloads.droppedPayloadLimit);
    assertEquals(3, payloads.degradedPayloadLimit);
    assertEquals(1, eventCount(parse(payloads.bodies.get(0))));
    final Map<String, Object> ev = firstEvent(parse(payloads.bodies.get(1)));
    assertObjectWithKey(ev.get("flag"), "second-flag");
    assertNull(ev.get("targeting_key"));
    assertNull(ev.get("context"));
  }

  @Test
  void oversizedDegradedPayloadRowIsDropped() {
    final FlagEvaluationPayloads.EncodedPayloads payloads =
        FlagEvaluationPayloads.buildPayloads(
            java.util.Collections.singletonList(
                event(repeat('f', 512), "on", "alloc1", null, 2, emptyMap())),
            CONTEXT,
            128);

    assertTrue(payloads.bodies.isEmpty());
    assertEquals(2, payloads.droppedPayloadLimit);
    assertEquals(0, payloads.degradedPayloadLimit);
  }

  @Test
  void oversizedFullPayloadRowIsDroppedWhenDegradedRowStillExceedsLimit() {
    final FlagEvaluationPayloads.EncodedPayloads payloads =
        FlagEvaluationPayloads.buildPayloads(
            java.util.Collections.singletonList(
                event(repeat('f', 512), "on", "alloc1", "user-1", 2, emptyMap())),
            CONTEXT,
            128);

    assertTrue(payloads.bodies.isEmpty());
    assertEquals(2, payloads.droppedPayloadLimit);
    assertEquals(0, payloads.degradedPayloadLimit);
  }

  @Test
  void errorPayloadSerializesErrorObject() throws Exception {
    final Map<String, Object> json =
        firstPayload(
            FlagEvaluationPayloads.buildPayloads(
                java.util.Collections.singletonList(
                    new FlagEvaluationPayloads.FlagEvaluationEvent(
                        EVAL_MS,
                        "err-flag",
                        EVAL_MS,
                        EVAL_MS,
                        1,
                        null,
                        null,
                        null,
                        true,
                        "type mismatch",
                        null)),
                CONTEXT,
                1_000_000));

    final Map<String, Object> ev = firstEvent(json);
    final Map<?, ?> error = (Map<?, ?>) ev.get("error");
    assertNotNull(error);
    assertEquals("type mismatch", error.get("message"));
    assertEquals(Boolean.TRUE, ev.get("runtime_default_used"));
  }

  @Test
  void emptyEventListProducesNoPayloads() {
    final FlagEvaluationPayloads.EncodedPayloads payloads =
        FlagEvaluationPayloads.buildPayloads(java.util.Collections.emptyList(), CONTEXT, 1_000_000);

    assertTrue(payloads.bodies.isEmpty());
    assertEquals(0, payloads.droppedPayloadLimit);
    assertEquals(0, payloads.degradedPayloadLimit);
  }

  @Test
  void requestDtoStoresContextAndEvents() {
    final List<FlagEvaluationPayloads.FlagEvaluationEvent> events =
        java.util.Collections.singletonList(
            event("dto-flag", "on", "alloc1", "user-1", 1, emptyMap()));

    final FlagEvaluationPayloads.FlagEvaluationsRequest request =
        new FlagEvaluationPayloads.FlagEvaluationsRequest(CONTEXT, events);

    assertEquals(CONTEXT, request.context);
    assertEquals(events, request.flagEvaluations);
  }

  @Test
  void degradingEventWithMissingOptionalFieldsKeepsOptionalObjectsAbsent() {
    final FlagEvaluationPayloads.FlagEvaluationEvent degraded =
        new FlagEvaluationPayloads.FlagEvaluationEvent(
                EVAL_MS,
                "default-flag",
                EVAL_MS,
                EVAL_MS,
                1,
                null,
                null,
                "user-1",
                true,
                null,
                null)
            .withoutTargetingKeyAndContext();

    assertNotNull(degraded);
    assertNull(degraded.variant);
    assertNull(degraded.allocation);
    assertNull(degraded.targeting_key);
    assertNull(degraded.context);
    assertNull(degraded.error);
    assertEquals(Boolean.TRUE, degraded.runtime_default_used);
  }

  @Test
  void emptyOptionalStringsAreTreatedAsAbsentWhenContextIsPresent() {
    final Map<String, Object> attrs = new HashMap<>();
    attrs.put("tier", "gold");
    final FlagEvaluationPayloads.FlagEvaluationEvent event =
        new FlagEvaluationPayloads.FlagEvaluationEvent(
            EVAL_MS, "empty-optionals", EVAL_MS, EVAL_MS, 1, "", "", null, false, "", attrs);

    assertNull(event.variant);
    assertNull(event.allocation);
    assertNull(event.error);
    assertNotNull(event.context);

    final FlagEvaluationPayloads.FlagEvaluationEvent degraded =
        event.withoutTargetingKeyAndContext();
    assertNotNull(degraded);
    assertNull(degraded.targeting_key);
    assertNull(degraded.context);
  }

  private static FlagEvaluationPayloads.FlagEvaluationEvent event(
      final String flagKey,
      final String variant,
      final String allocation,
      final String targetingKey,
      final long count,
      final Map<String, Object> attrs) {
    return new FlagEvaluationPayloads.FlagEvaluationEvent(
        EVAL_MS,
        flagKey,
        EVAL_MS,
        EVAL_MS,
        count,
        variant,
        allocation,
        targetingKey,
        false,
        null,
        attrs);
  }

  private static Map<String, Object> firstPayload(
      final FlagEvaluationPayloads.EncodedPayloads payloads) throws Exception {
    assertEquals(1, payloads.bodies.size());
    return parse(payloads.bodies.get(0));
  }

  private static Map<String, Object> parse(final byte[] body) throws Exception {
    return JSON_MAP.fromJson(new String(body, java.nio.charset.StandardCharsets.UTF_8));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> firstEvent(final Map<String, Object> batch) {
    final List<Object> events = (List<Object>) batch.get("flagEvaluations");
    assertNotNull(events);
    assertFalse(events.isEmpty());
    return (Map<String, Object>) events.get(0);
  }

  @SuppressWarnings("unchecked")
  private static int eventCount(final Map<String, Object> batch) {
    final List<Object> events = (List<Object>) batch.get("flagEvaluations");
    assertNotNull(events);
    return events.size();
  }

  private static void assertObjectWithKey(final Object object, final String expectedKey) {
    assertTrue(object instanceof Map);
    assertEquals(expectedKey, ((Map<?, ?>) object).get("key"));
  }

  private static String repeat(final char c, final int count) {
    final char[] chars = new char[count];
    java.util.Arrays.fill(chars, c);
    return new String(chars);
  }

  private static Map<String, String> context() {
    final Map<String, String> context = new HashMap<>();
    context.put("service", "test-service");
    return context;
  }
}
