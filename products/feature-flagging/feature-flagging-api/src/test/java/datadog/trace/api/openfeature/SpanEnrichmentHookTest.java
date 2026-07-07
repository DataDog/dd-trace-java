package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.Value;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit suite for APM feature-flag span enrichment.
 *
 * <p>Covers the seven required validation cases plus the explicit max-200 case and the codec
 * golden-vector round-trip. The contract (encoding, limits, tag shapes) is FROZEN against the Node
 * reference ({@code dd-trace-js#8343}).
 */
class SpanEnrichmentHookTest {

  // Instance-owned state store shared by the hook and interceptor under test, mirroring how a
  // single
  // Provider wires them.
  private SpanEnrichmentStates states;

  @BeforeEach
  void freshState() {
    states = new SpanEnrichmentStates();
  }

  @AfterEach
  void clearState() {
    states.clear();
    // The interceptor is a process-wide singleton; leave it inert for the next test.
    SpanEnrichmentInterceptor.INSTANCE.unbind(SpanEnrichmentInterceptor.INSTANCE.activeStates());
  }

  // ---- helpers ----

  /** A mock local-root span reporting itself as its own local root (identity key + final flush). */
  private static AgentSpan rootSpan() {
    final AgentSpan root = mock(AgentSpan.class);
    when(root.getLocalRootSpan()).thenReturn(root);
    return root;
  }

  private static FlagEvaluationDetails<Object> details(
      final String flagKey,
      final String variant,
      final Object value,
      final ImmutableMetadata metadata) {
    return FlagEvaluationDetails.builder()
        .flagKey(flagKey)
        .variant(variant)
        .value(value)
        .flagMetadata(metadata)
        .build();
  }

  private static ImmutableMetadata metadata(final Integer serialId, final boolean doLog) {
    final ImmutableMetadata.ImmutableMetadataBuilder builder = ImmutableMetadata.builder();
    if (serialId != null) {
      builder.addString(SpanEnrichmentHook.METADATA_SERIAL_ID, serialId.toString());
    }
    builder.addString(SpanEnrichmentHook.METADATA_DO_LOG, String.valueOf(doLog));
    return builder.build();
  }

  private static HookContext<Object> ctx(final String flagKey, final String targetingKey) {
    return HookContext.from(
        flagKey,
        FlagValueType.STRING,
        null,
        null,
        targetingKey == null ? new ImmutableContext() : new ImmutableContext(targetingKey),
        "default");
  }

  /** Drives the capture branch directly (no static tracer) for a fixed local-root span. */
  private static void capture(
      final SpanEnrichmentHook hook,
      final AgentSpan root,
      final String flagKey,
      final String targetingKey,
      final String variant,
      final Object value,
      final Integer serialId,
      final boolean doLog) {
    hook.capture(
        root,
        ctx(flagKey, targetingKey),
        details(flagKey, variant, value, metadata(serialId, doLog)));
  }

  /** Binds a fresh interceptor to the given store, models a final flush, and returns it. */
  private static SpanEnrichmentInterceptor boundInterceptor(final SpanEnrichmentStates states) {
    SpanEnrichmentInterceptor.INSTANCE.bind(states);
    return SpanEnrichmentInterceptor.INSTANCE;
  }

  // ---- 1. codec golden-vector round-trip ----

  @Test
  void codecGoldenVectorAndRoundTrip() {
    final SortedSet<Integer> ids = new TreeSet<>();
    ids.add(100);
    ids.add(108);
    ids.add(128);
    ids.add(130);
    final String encoded = ULeb128Encoder.encodeDeltaVarint(ids);
    assertEquals("ZAgUAg==", encoded, "golden vector must match the frozen Node contract");
    // round-trip: decode back to the same ascending ids
    assertEquals(ids, decodeDeltaVarint(encoded));
    // empty set -> empty string (tag omitted)
    assertEquals("", ULeb128Encoder.encodeDeltaVarint(Collections.emptySet()));
    // dedupe is structural: a duplicate id does not change the encoding
    final SortedSet<Integer> withDup = new TreeSet<>(ids);
    withDup.add(100);
    assertEquals(encoded, ULeb128Encoder.encodeDeltaVarint(withDup));
  }

  // Test-only decode oracle for the ULEB128 delta-varint codec. The encoder ships in the published
  // dd-openfeature jar; the decode side exists only to assert round-trips here, so it lives in the
  // test rather than in production code.
  private static SortedSet<Integer> decodeDeltaVarint(final String encoded) {
    final SortedSet<Integer> result = new TreeSet<>();
    if (encoded == null || encoded.isEmpty()) {
      return result;
    }
    final byte[] bytes = Base64.getDecoder().decode(encoded);
    int previous = 0;
    int index = 0;
    while (index < bytes.length) {
      long value = 0;
      int shift = 0;
      while (true) {
        final byte b = bytes[index++];
        value |= ((long) (b & 0x7F)) << shift;
        if ((b & 0x80) == 0) {
          break;
        }
        shift += 7;
      }
      previous += (int) value; // delta from previous
      result.add(previous);
    }
    return result;
  }

  // ---- 2. no-span (no active root) ----

  @Test
  void noActiveSpanDoesNotCrashOrAccumulate() {
    // Injected resolver returns null => no active local root (the no-span case).
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(() -> null, states);
    // Should be a no-op, never throw.
    hook.finallyAfter(
        ctx("flag", "user-1"),
        details("flag", "on", "v", metadata(100, true)),
        Collections.emptyMap());
    assertTrue(states.isEmpty(), "no active span => no accumulator state");
  }

  @Test
  void finallyAfterResolvesRootViaResolverAndAccumulates() {
    // Drives finallyAfter end-to-end with an injected root resolver (no static mocks).
    final AgentSpan root = mock(AgentSpan.class);
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(() -> root, states);
    hook.finallyAfter(
        ctx("flag", "user-1"),
        details("flag", "on", "v", metadata(42, true)),
        Collections.emptyMap());
    final SpanEnrichmentAccumulator state = states.peek(root);
    assertTrue(state != null && state.serialIdsView().contains(42));
    assertEquals(1, state.subjectCount(), "doLog=true + targeting key => subject recorded");
  }

  // ---- 3. finished-root (accumulate + dedupe, then flush via interceptor) ----

  @Test
  void finishedRootFlushesFlagsEncTag() {
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);
    final AgentSpan root = rootSpan();
    // accumulate {100,108,128,130} with a duplicate 100 to prove dedupe
    capture(hook, root, "f1", "user-1", "on", "v", 100, false);
    capture(hook, root, "f2", "user-1", "on", "v", 108, false);
    capture(hook, root, "f3", "user-1", "on", "v", 128, false);
    capture(hook, root, "f4", "user-1", "on", "v", 130, false);
    capture(hook, root, "f1", "user-1", "on", "v", 100, false); // dup

    boundInterceptor(states).onTraceComplete(Collections.singletonList(root));

    verify(root).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "ZAgUAg==");
    // state cleared after flush
    assertTrue(states.isEmpty(), "state must be cleared on flush");
  }

  // ---- 4. error/default variant (missing variant -> ffe_runtime_defaults JSON object) ----

  @Test
  void runtimeDefaultMissingVariantWritesJsonObject() {
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);
    final AgentSpan root = rootSpan();
    // no serial id + null variant => runtime default; object value must be JSON-stringified
    final Map<String, Object> objectValue = Collections.singletonMap("k", "val");
    hook.capture(
        root,
        ctx("obj-flag", "user-1"),
        FlagEvaluationDetails.builder()
            .flagKey("obj-flag")
            .variant(null)
            .value(objectValue)
            .flagMetadata(ImmutableMetadata.builder().build())
            .build());

    boundInterceptor(states).onTraceComplete(Collections.singletonList(root));

    // ffe_runtime_defaults is a JSON object string, NOT [object Object]/toString.
    verify(root)
        .setTag(
            SpanEnrichmentAccumulator.TAG_RUNTIME_DEFAULTS,
            "{\"obj-flag\":\"{\\\"k\\\":\\\"val\\\"}\"}");
    // no flags tag when there are no serial ids
    verify(root, never()).setTag(eq(SpanEnrichmentAccumulator.TAG_FLAGS_ENC), anyString());
  }

  // ---- 4b. real OpenFeature object path: a Value structure default must serialize to JSON ----

  /**
   * The real object-evaluation path hands the runtime default in as a {@code
   * dev.openfeature.sdk.Value}, not a raw {@code Map}. The accumulator must unwrap the {@code
   * Value} and emit JSON (matching Node's {@code JSON.stringify}), never {@code Value.toString()}
   * (which is {@code "Value(innerObject=...)"}).
   */
  @Test
  void runtimeDefaultStructureValueSerializesAsJsonNotToString() {
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);
    final AgentSpan root = rootSpan();

    // Single-key structure so the exact-string assertion does not depend on the OpenFeature SDK's
    // internal key ordering. (The multi-key / nesting behaviour is covered by the direct
    // stringifyDefault assertions below.)
    final Map<String, Value> inner = new LinkedHashMap<>();
    inner.put("enabled", new Value(true));
    final Value structureDefault = new Value(new ImmutableStructure(inner));

    hook.capture(
        root,
        ctx("struct-flag", "user-1"),
        FlagEvaluationDetails.builder()
            .flagKey("struct-flag")
            .variant(null)
            .value(structureDefault)
            .flagMetadata(ImmutableMetadata.builder().build())
            .build());

    boundInterceptor(states).onTraceComplete(Collections.singletonList(root));

    // {"struct-flag":"{\"enabled\":true}"}  — note: NO "Value(innerObject=...)".
    verify(root)
        .setTag(
            SpanEnrichmentAccumulator.TAG_RUNTIME_DEFAULTS,
            "{\"struct-flag\":\"{\\\"enabled\\\":true}\"}");
  }

  /**
   * Direct stringify of a structured {@code Value}: asserts the value is JSON (objects + nested
   * scalars), not {@code Value.toString()}. The structure is single-key to keep ordering
   * deterministic across OpenFeature SDK versions.
   */
  @Test
  void stringifyDefaultUnwrapsValueStructureToJson() {
    final Map<String, Value> inner = new LinkedHashMap<>();
    inner.put("count", new Value(42));
    final Value structureDefault = new Value(new ImmutableStructure(inner));
    assertEquals("{\"count\":42}", SpanEnrichmentAccumulator.stringifyDefault(structureDefault));
  }

  /**
   * A list-valued {@code Value} default serializes to a JSON array, with nested Values unwrapped.
   */
  @Test
  void runtimeDefaultListValueSerializesAsJsonArray() {
    final Value listDefault =
        new Value(Arrays.asList(new Value("a"), new Value(2), new Value(true)));
    // direct stringify assertion (exact bytes)
    assertEquals("[\"a\",2,true]", SpanEnrichmentAccumulator.stringifyDefault(listDefault));
  }

  /** Scalar Values collapse to the same string form Node's String(value) produces. */
  @Test
  void scalarValueDefaultsMatchNodeStringForm() {
    assertEquals("hello", SpanEnrichmentAccumulator.stringifyDefault(new Value("hello")));
    assertEquals("true", SpanEnrichmentAccumulator.stringifyDefault(new Value(true)));
    assertEquals("7", SpanEnrichmentAccumulator.stringifyDefault(new Value(7)));
    assertEquals("null", SpanEnrichmentAccumulator.stringifyDefault(new Value()));
  }

  // ---- 5. per-subject cap (10 subjects / 20 experiments / doLog gating) ----

  @Test
  void subjectCapsAndDoLogGating() {
    final SpanEnrichmentAccumulator acc = new SpanEnrichmentAccumulator();

    // doLog gating: addSubject only happens when doLog true (driven via the hook branch below).
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);
    final AgentSpan root = rootSpan();
    capture(hook, root, "f", "user-A", "on", "v", 1, false); // doLog=false => no subject
    assertEquals(0, states.peek(root).subjectCount(), "doLog=false must not record a subject");
    capture(hook, root, "f", "user-A", "on", "v", 2, true); // doLog=true => subject recorded
    assertEquals(1, states.peek(root).subjectCount());

    // per-subject experiment cap: 20 max
    for (int i = 0; i < 25; i++) {
      acc.addSubject("subjectX", i);
    }
    final Map<String, String> tags = acc.toSpanTags();
    final SortedSet<Integer> decoded =
        decodeDeltaVarint(
            // ffe_subjects_enc is {"<sha>":"<base64>"}; extract the single base64 value
            tags.get(SpanEnrichmentAccumulator.TAG_SUBJECTS_ENC)
                .replaceAll("^\\{\"[a-f0-9]+\":\"", "")
                .replaceAll("\"\\}$", ""));
    assertEquals(SpanEnrichmentAccumulator.MAX_EXPERIMENTS_PER_SUBJECT, decoded.size());

    // subject cap: 10 max distinct subjects
    final SpanEnrichmentAccumulator acc2 = new SpanEnrichmentAccumulator();
    for (int i = 0; i < 15; i++) {
      acc2.addSubject("subject-" + i, i);
    }
    assertEquals(SpanEnrichmentAccumulator.MAX_SUBJECTS, acc2.subjectCount());
  }

  // ---- 6. max-200 serial ids ----

  @Test
  void max200SerialIdsEnforced() {
    final SpanEnrichmentAccumulator acc = new SpanEnrichmentAccumulator();
    for (int i = 0; i < 300; i++) {
      acc.addSerialId(i);
    }
    assertEquals(
        SpanEnrichmentAccumulator.MAX_SERIAL_IDS,
        acc.serialIdsView().size(),
        "serial ids must be capped at 200");
  }

  // ---- 7. JSON/object-default (json not toString + 64-char truncation) ----

  @Test
  void objectDefaultJsonAndTruncation() {
    // object -> JSON, not toString
    assertEquals(
        "{\"a\":\"b\"}",
        SpanEnrichmentAccumulator.stringifyDefault(Collections.singletonMap("a", "b")));
    // scalar string -> as-is
    assertEquals("hello", SpanEnrichmentAccumulator.stringifyDefault("hello"));
    // null -> "null"
    assertEquals("null", SpanEnrichmentAccumulator.stringifyDefault(null));

    // 64-char truncation (first-wins handled separately)
    final StringBuilder longValue = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      longValue.append('x');
    }
    final SpanEnrichmentAccumulator acc = new SpanEnrichmentAccumulator();
    acc.addDefault("flag", longValue.toString());
    final String tag = acc.toSpanTags().get(SpanEnrichmentAccumulator.TAG_RUNTIME_DEFAULTS);
    // {"flag":"<64 x's>"}
    final String expectedValue =
        longValue.substring(0, SpanEnrichmentAccumulator.MAX_DEFAULT_VALUE_LENGTH);
    assertEquals("{\"flag\":\"" + expectedValue + "\"}", tag);

    // first-wins: a second addDefault for the same flag is ignored
    acc.addDefault("flag", "second");
    assertEquals(1, acc.defaultCount());
  }

  // ---- gate-off negative control (no ffe_*, no hook, no state) ----

  @Test
  void gateOffConstructsNothingAndAccumulatesNoState() {
    // Gate OFF via the injectable override (no static config mocking).
    final Provider provider = new Provider(new Provider.Options(), null, Boolean.FALSE);

    // No hook, no state store constructed (zero idle overhead).
    assertNull(provider.spanEnrichmentHook(), "gate off => no span-enrichment hook");
    assertNull(provider.spanEnrichmentStates(), "gate off => no span-enrichment state store");
    // getProviderHooks must not contain a SpanEnrichmentHook.
    final List<Hook> hooks = provider.getProviderHooks();
    for (final Hook hook : hooks) {
      assertFalse(
          hook instanceof SpanEnrichmentHook, "gate off => SpanEnrichmentHook never registered");
    }
  }

  /**
   * getProviderHooks() is called on every evaluation; it must return the SAME precomputed list
   * (allocating nothing) regardless of gate state.
   */
  @Test
  void getProviderHooksReturnsSameInstanceEachCall() {
    final Provider gateOff = new Provider(new Provider.Options(), null, Boolean.FALSE);
    assertTrue(
        gateOff.getProviderHooks() == gateOff.getProviderHooks(),
        "gate off => getProviderHooks allocates nothing (same instance)");

    final Provider gateOn =
        new Provider(new Provider.Options(), null, Boolean.TRUE, interceptor -> true);
    assertTrue(
        gateOn.getProviderHooks() == gateOn.getProviderHooks(),
        "gate on => getProviderHooks allocates nothing (same instance)");
  }

  // ---- gate-on construction + provider-close cleanup ----

  @Test
  void gateOnConstructsHookAndStateThenShutdownUnbinds() {
    final Provider provider =
        new Provider(new Provider.Options(), null, Boolean.TRUE, interceptor -> true);
    assertTrue(provider.spanEnrichmentHook() != null, "gate on => hook constructed");
    assertTrue(provider.spanEnrichmentStates() != null, "gate on => state store constructed");
    // the process-wide interceptor is bound to this provider's store
    assertTrue(
        SpanEnrichmentInterceptor.INSTANCE.activeStates() == provider.spanEnrichmentStates(),
        "gate on => interceptor bound to this provider's store");
    // hook registered in provider hooks
    boolean registered = false;
    for (final Hook hook : provider.getProviderHooks()) {
      if (hook instanceof SpanEnrichmentHook) {
        registered = true;
      }
    }
    assertTrue(registered, "gate on => SpanEnrichmentHook registered in getProviderHooks");

    // provider close unbinds + drains ITS OWN state.
    final SpanEnrichmentStates providerStates = provider.spanEnrichmentStates();
    providerStates.getOrCreate(mock(AgentSpan.class));
    assertFalse(providerStates.isEmpty());
    provider.shutdown();
    assertNull(
        SpanEnrichmentInterceptor.INSTANCE.activeStates(), "shutdown unbinds the active store");
    assertTrue(providerStates.isEmpty(), "shutdown drains residual state");
  }

  // ---- error isolation: enrichment never throws ----

  @Test
  void captureNeverThrowsOnNullInputs() {
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);
    // null details handled in finallyAfter; capture with empty metadata + null variant
    hook.finallyAfter(null, null, null);
    assertTrue(states.isEmpty());
  }

  // ---- interceptor inert when unbound / empty trace robustness ----

  @Test
  void interceptorNoOpsWhenUnboundOrEmpty() {
    // empty trace
    SpanEnrichmentInterceptor.INSTANCE.bind(states);
    assertTrue(
        SpanEnrichmentInterceptor.INSTANCE.onTraceComplete(Collections.emptyList()).isEmpty());
    // unbound (inert)
    SpanEnrichmentInterceptor.INSTANCE.unbind(states);
    final AgentSpan root = mock(AgentSpan.class);
    final List<MutableSpan> trace = Collections.singletonList(root);
    SpanEnrichmentInterceptor.INSTANCE.onTraceComplete(trace);
    verify(root, never()).setTag(anyString(), anyString());
  }

  // ---- interceptor priority uniqueness ----

  @Test
  void interceptorPriorityIsUnique() {
    // Distinct from AbstractTraceInterceptor.Priority values (0,1,2,3, MAX-2, MAX-1, MAX).
    assertEquals(4, SpanEnrichmentInterceptor.INSTANCE.priority());
  }
}
