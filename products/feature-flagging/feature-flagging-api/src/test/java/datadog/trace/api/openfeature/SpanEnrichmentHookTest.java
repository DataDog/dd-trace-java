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

import datadog.trace.api.DDTraceId;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L0 unit suite for APM feature-flag span enrichment (JAVA-01).
 *
 * <p>Covers the seven required VALIDATION.md cases plus the explicit max-200 case and the codec
 * golden-vector round-trip. The contract (encoding, limits, tag shapes) is FROZEN against the Node
 * reference ({@code dd-trace-js#8343}).
 */
class SpanEnrichmentHookTest {

  // Per-test instance-owned state store (replaces the former global static). The hook and the
  // interceptor under test share this one, mirroring how a single Provider wires them (CR-03).
  private SpanEnrichmentStates states;

  @BeforeEach
  void freshState() {
    states = new SpanEnrichmentStates();
  }

  @AfterEach
  void clearState() {
    states.clear();
  }

  // ---- helpers ----

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

  /** Drives the capture branch directly (no static tracer) for a fixed trace key. */
  private static void capture(
      final SpanEnrichmentHook hook,
      final long traceKey,
      final String flagKey,
      final String targetingKey,
      final String variant,
      final Object value,
      final Integer serialId,
      final boolean doLog) {
    hook.capture(
        traceKey,
        ctx(flagKey, targetingKey),
        details(flagKey, variant, value, metadata(serialId, doLog)));
  }

  /**
   * Configures a mock root span to report itself as its own local root with the given trace id. The
   * returned span, passed in a singleton collection, models a FINAL flush (the root is present in
   * the fragment), so the interceptor flushes + removes state (CR-01 final-write path).
   */
  private static AgentSpan rootSpanCollection(final long traceId, final AgentSpan rootSpan) {
    when(rootSpan.getLocalRootSpan()).thenReturn(rootSpan);
    when(rootSpan.getTraceId()).thenReturn(DDTraceId.from(traceId));
    return rootSpan;
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
    assertEquals(ids, ULeb128Encoder.decodeDeltaVarint(encoded));
    // empty set -> empty string (tag omitted)
    assertEquals("", ULeb128Encoder.encodeDeltaVarint(Collections.emptySet()));
    // dedupe is structural: a duplicate id does not change the encoding
    final SortedSet<Integer> withDup = new TreeSet<>(ids);
    withDup.add(100);
    assertEquals(encoded, ULeb128Encoder.encodeDeltaVarint(withDup));
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
    when(root.getTraceId()).thenReturn(DDTraceId.from(0x77L));
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(() -> root, states);
    hook.finallyAfter(
        ctx("flag", "user-1"),
        details("flag", "on", "v", metadata(42, true)),
        Collections.emptyMap());
    final SpanEnrichmentAccumulator state = states.peek(0x77L);
    assertTrue(state != null && state.serialIdsView().contains(42));
    assertEquals(1, state.subjectCount(), "doLog=true + targeting key => subject recorded");
  }

  // ---- 3. finished-root (accumulate + dedupe, then flush via interceptor) ----

  @Test
  void finishedRootFlushesFlagsEncTag() {
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);
    final long traceId = 0xABCDL;
    // accumulate {100,108,128,130} with a duplicate 100 to prove dedupe
    capture(hook, traceId, "f1", "user-1", "on", "v", 100, false);
    capture(hook, traceId, "f2", "user-1", "on", "v", 108, false);
    capture(hook, traceId, "f3", "user-1", "on", "v", 128, false);
    capture(hook, traceId, "f4", "user-1", "on", "v", 130, false);
    capture(hook, traceId, "f1", "user-1", "on", "v", 100, false); // dup

    final AgentSpan root = mock(AgentSpan.class);
    rootSpanCollection(traceId, root);
    final SpanEnrichmentInterceptor interceptor = new SpanEnrichmentInterceptor(states);

    interceptor.onTraceComplete(Collections.singletonList(root));

    verify(root).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "ZAgUAg==");
    // state cleared after flush
    assertTrue(states.isEmpty(), "state must be cleared on flush");
  }

  // ---- 4. error/default variant (missing variant -> ffe_runtime_defaults JSON object) ----

  @Test
  void runtimeDefaultMissingVariantWritesJsonObject() {
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);
    final long traceId = 0x1234L;
    // no serial id + null variant => runtime default; object value must be JSON-stringified
    final Map<String, Object> objectValue = Collections.singletonMap("k", "val");
    hook.capture(
        traceId,
        ctx("obj-flag", "user-1"),
        FlagEvaluationDetails.builder()
            .flagKey("obj-flag")
            .variant(null)
            .value(objectValue)
            .flagMetadata(ImmutableMetadata.builder().build())
            .build());

    final AgentSpan root = mock(AgentSpan.class);
    rootSpanCollection(traceId, root);
    new SpanEnrichmentInterceptor(states).onTraceComplete(Collections.singletonList(root));

    // ffe_runtime_defaults is a JSON object string, NOT [object Object]/toString.
    verify(root)
        .setTag(
            SpanEnrichmentAccumulator.TAG_RUNTIME_DEFAULTS,
            "{\"obj-flag\":\"{\\\"k\\\":\\\"val\\\"}\"}");
    // no flags tag when there are no serial ids
    verify(root, never()).setTag(eq(SpanEnrichmentAccumulator.TAG_FLAGS_ENC), anyString());
  }

  // ---- 5. per-subject cap (10 subjects / 20 experiments / doLog gating) ----

  @Test
  void subjectCapsAndDoLogGating() {
    final SpanEnrichmentAccumulator acc = new SpanEnrichmentAccumulator();

    // doLog gating: addSubject only happens when doLog true (driven via the hook branch below).
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);
    final long traceId = 0x5L;
    capture(hook, traceId, "f", "user-A", "on", "v", 1, false); // doLog=false => no subject
    assertEquals(0, states.peek(traceId).subjectCount(), "doLog=false must not record a subject");
    capture(hook, traceId, "f", "user-A", "on", "v", 2, true); // doLog=true => subject recorded
    assertEquals(1, states.peek(traceId).subjectCount());

    // per-subject experiment cap: 20 max
    for (int i = 0; i < 25; i++) {
      acc.addSubject("subjectX", i);
    }
    final Map<String, String> tags = acc.toSpanTags();
    final SortedSet<Integer> decoded =
        ULeb128Encoder.decodeDeltaVarint(
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

  // ---- gate-off negative control (no ffe_*, no hook/interceptor, no state) (DG-005) ----

  @Test
  void gateOffConstructsNothingAndAccumulatesNoState() {
    // Gate OFF via the injectable override (no static config mocking).
    final Provider provider = new Provider(new Provider.Options(), null, Boolean.FALSE);

    // No hook, no interceptor constructed (DG-005 zero-idle-overhead).
    assertNull(provider.spanEnrichmentHook(), "gate off => no span-enrichment hook");
    assertNull(provider.spanEnrichmentInterceptor(), "gate off => no span-enrichment interceptor");
    // getProviderHooks must not contain a SpanEnrichmentHook.
    final List<Hook> hooks = provider.getProviderHooks();
    for (final Hook hook : hooks) {
      assertFalse(
          hook instanceof SpanEnrichmentHook, "gate off => SpanEnrichmentHook never registered");
    }
    // No state store is created at all when the gate is off: the per-provider store lives only
    // inside the gate-on branch, so a null interceptor means no store and no idle overhead
    // (DG-005).
    assertNull(
        provider.spanEnrichmentInterceptor(),
        "gate off => no interceptor, hence no state store and no accumulator state");
  }

  // ---- gate-on construction + provider-close cleanup ----

  @Test
  void gateOnConstructsHookAndInterceptorThenShutdownDisables() {
    // Gate ON via the injectable override; registration succeeds (injected registrar returns true)
    // so we don't mutate the global tracer (the Noop tracer rejects, which is the CR-03 path tested
    // separately in secondProviderRejectedRegistrationDoesNotClearFirstProviderState).
    final Provider provider =
        new Provider(new Provider.Options(), null, Boolean.TRUE, interceptor -> true);
    assertTrue(provider.spanEnrichmentHook() != null, "gate on => hook constructed");
    assertTrue(provider.spanEnrichmentInterceptor() != null, "gate on => interceptor constructed");
    assertTrue(provider.spanEnrichmentInterceptor().isEnabled());
    // hook registered in provider hooks
    boolean registered = false;
    for (final Hook hook : provider.getProviderHooks()) {
      if (hook instanceof SpanEnrichmentHook) {
        registered = true;
      }
    }
    assertTrue(registered, "gate on => SpanEnrichmentHook registered in getProviderHooks");

    // provider close disables the interceptor (provider-close cleanup) and drains ITS OWN state.
    final SpanEnrichmentStates providerStates = provider.spanEnrichmentInterceptor().states();
    providerStates.getOrCreate(1L);
    assertFalse(providerStates.isEmpty());
    provider.shutdown();
    assertFalse(provider.spanEnrichmentInterceptor().isEnabled(), "shutdown disables interceptor");
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

  // ---- interceptor gate-off / empty trace robustness ----

  @Test
  void interceptorNoOpsWhenDisabledOrEmpty() {
    final SpanEnrichmentInterceptor interceptor = new SpanEnrichmentInterceptor(states);
    // empty trace
    assertTrue(interceptor.onTraceComplete(Collections.emptyList()).isEmpty());
    // disabled
    interceptor.disable();
    final AgentSpan root = mock(AgentSpan.class);
    final List<MutableSpan> trace = Collections.singletonList(root);
    interceptor.onTraceComplete(trace);
    verify(root, never()).setTag(anyString(), anyString());
  }

  // ---- interceptor priority uniqueness ----

  @Test
  void interceptorPriorityIsUnique() {
    // Distinct from AbstractTraceInterceptor.Priority values (0,1,2,3, MAX-2, MAX-1, MAX).
    assertEquals(4, new SpanEnrichmentInterceptor(states).priority());
  }
}
