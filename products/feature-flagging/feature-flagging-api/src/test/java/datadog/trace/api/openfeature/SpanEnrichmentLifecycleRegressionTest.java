package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Regression suite for the per-root-span lifecycle and reconfiguration behaviour of span
 * enrichment. Each test exercises a scenario the single-root, single-thread, single-provider happy
 * path does not cover.
 *
 * <ul>
 *   <li>partial flush — a fragment of child spans only (the still-open root is excluded by
 *       dd-trace-core) must NOT drain the accumulator; pre-flush flags must survive onto the root
 *       at final completion.
 *   <li>unbounded leak — traces that never reach the interceptor (dropped / Noop / never-finishing)
 *       must not leak accumulator entries unboundedly; the store is hard-bounded.
 *   <li>reconfiguration — after a provider closes, a new gate-on provider must still enrich (the
 *       process-wide interceptor rebinds), and a closing provider must never clobber a newer
 *       provider's in-flight state.
 *   <li>128-bit trace ids — two distinct 128-bit traces that share their low-order 64 bits must not
 *       merge enrichment state.
 * </ul>
 */
class SpanEnrichmentLifecycleRegressionTest {

  @AfterEach
  void resetInterceptor() {
    // The interceptor is a process-wide singleton; leave it inert for the next test.
    SpanEnrichmentInterceptor.INSTANCE.unbind(SpanEnrichmentInterceptor.INSTANCE.activeStates());
  }

  // ---- helpers ----

  private static String key(final long traceId) {
    return DDTraceId.from(traceId).toHexString();
  }

  private static ImmutableMetadata serialMeta(final int serialId, final boolean doLog) {
    return ImmutableMetadata.builder()
        .addString(SpanEnrichmentHook.METADATA_SERIAL_ID, Integer.toString(serialId))
        .addString(SpanEnrichmentHook.METADATA_DO_LOG, String.valueOf(doLog))
        .build();
  }

  private static FlagEvaluationDetails<Object> serialDetails(
      final String flagKey, final int serialId, final boolean doLog) {
    return FlagEvaluationDetails.builder()
        .flagKey(flagKey)
        .variant("on")
        .value("v")
        .flagMetadata(serialMeta(serialId, doLog))
        .build();
  }

  private static HookContext<Object> ctx(final String flagKey, final String targetingKey) {
    final EvaluationContext ec =
        targetingKey == null ? new ImmutableContext() : new ImmutableContext(targetingKey);
    return HookContext.from(flagKey, FlagValueType.STRING, null, null, ec, "default");
  }

  /** A mock child span whose local root is {@code root} but which is itself NOT the root. */
  private static AgentSpan childOf(final AgentSpan root) {
    final AgentSpan child = mock(AgentSpan.class);
    when(child.getLocalRootSpan()).thenReturn(root);
    return child;
  }

  /** A mock local-root span reporting itself as its own local root with the given trace id. */
  private static AgentSpan rootSpan(final DDTraceId traceId) {
    final AgentSpan root = mock(AgentSpan.class);
    when(root.getLocalRootSpan()).thenReturn(root);
    when(root.getTraceId()).thenReturn(traceId);
    return root;
  }

  private static AgentSpan rootSpan(final long traceId) {
    return rootSpan(DDTraceId.from(traceId));
  }

  // =====================================================================================
  // partial flush must not drop captured state or misattribute tags
  // =====================================================================================

  /**
   * Models the real dd-trace-core sequence for a long-running trace:
   *
   * <ol>
   *   <li>flags are evaluated (captured into the accumulator),
   *   <li>a PARTIAL flush fires {@code onTraceComplete} with a fragment of CHILD spans only — the
   *       still-open local root is excluded (held back via {@code rootSpanWritten}),
   *   <li>more flags are evaluated,
   *   <li>the FINAL flush fires {@code onTraceComplete} with the root present.
   * </ol>
   *
   * All serial ids — both pre- and post-partial-flush — must appear on the root's {@code
   * ffe_flags_enc} tag, and nothing must be written on the partial flush.
   */
  @Test
  void partialFlushExcludingRootPreservesPreFlushFlags() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);
    final SpanEnrichmentInterceptor interceptor = SpanEnrichmentInterceptor.INSTANCE;
    interceptor.bind(states);

    final long traceId = 0x10A6L;
    final AgentSpan root = rootSpan(traceId);

    // (1) pre-partial-flush evaluations
    hook.capture(key(traceId), ctx("f1", "user-1"), serialDetails("f1", 100, false));
    hook.capture(key(traceId), ctx("f2", "user-1"), serialDetails("f2", 108, false));

    // (2) PARTIAL flush: a fragment of children only — the open root is NOT in the collection.
    final AgentSpan child1 = childOf(root);
    final AgentSpan child2 = childOf(root);
    final List<MutableSpan> partialFragment = Arrays.asList(child1, child2);
    interceptor.onTraceComplete(partialFragment);

    // No tags may be written on a partial flush, and the accumulator must survive intact.
    verify(child1, never()).setTag(anyString(), anyString());
    verify(child2, never()).setTag(anyString(), anyString());
    verify(root, never()).setTag(anyString(), anyString());
    final SpanEnrichmentAccumulator surviving = states.peek(key(traceId));
    assertNotNull(surviving, "partial flush must NOT remove the accumulator");
    assertTrue(
        surviving.serialIdsView().contains(100) && surviving.serialIdsView().contains(108),
        "pre-flush serial ids must survive a partial flush");

    // (3) more evaluations after the partial flush
    hook.capture(key(traceId), ctx("f3", "user-1"), serialDetails("f3", 128, false));
    hook.capture(key(traceId), ctx("f4", "user-1"), serialDetails("f4", 130, false));

    // (4) FINAL flush: the root is present in the fragment (alongside a late child).
    final AgentSpan lateChild = childOf(root);
    interceptor.onTraceComplete(Arrays.asList(lateChild, root));

    // The full set {100,108,128,130} -> golden "ZAgUAg==" must land on the root.
    verify(root).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "ZAgUAg==");
    assertTrue(states.isEmpty(), "state must be removed only on the final flush");
  }

  /**
   * A partial flush whose first span reports a non-null local root that is absent from the fragment
   * must be treated as "not the final write" and leave state intact — even when there are several
   * children. Guards the "never tag a non-root span" concern.
   */
  @Test
  void partialFlushNeverWritesTagsOnAChildSpan() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);
    final SpanEnrichmentInterceptor interceptor = SpanEnrichmentInterceptor.INSTANCE;
    interceptor.bind(states);

    final long traceId = 0xC0DEL;
    final AgentSpan root = rootSpan(traceId);
    hook.capture(key(traceId), ctx("f", "user-1"), serialDetails("f", 7, false));

    final AgentSpan child = childOf(root);
    interceptor.onTraceComplete(Collections.singletonList(child));

    verify(child, never()).setTag(anyString(), anyString());
    verify(root, never()).setTag(anyString(), anyString());
    assertNotNull(states.peek(key(traceId)), "child-only fragment must keep state");
  }

  // =====================================================================================
  // never-completing traces must not leak accumulator entries unboundedly
  // =====================================================================================

  /**
   * Simulates a sustained stream of traces that each evaluate a flag but never reach the
   * interceptor (dropped traces / Noop tracer / never-finishing roots). The store must stay bounded
   * by {@link SpanEnrichmentStates#MAX_TRACES} rather than growing without limit.
   */
  @Test
  void neverCompletingTracesDoNotLeakUnbounded() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);

    final int churn = SpanEnrichmentStates.MAX_TRACES * 3;
    for (int i = 0; i < churn; i++) {
      // Each distinct trace id accumulates once and is NEVER flushed (no interceptor call).
      hook.capture(key(i), ctx("f", "user-" + i), serialDetails("f", i % 1000 + 1, false));
      assertTrue(
          states.size() <= SpanEnrichmentStates.MAX_TRACES,
          "state store must stay bounded for never-completing traces");
    }
    assertEquals(
        SpanEnrichmentStates.MAX_TRACES, states.size(), "store saturates at the cap, never beyond");
  }

  /**
   * Bounding is FIFO by insertion: once the cap is reached, the oldest entries are evicted so the
   * newest in-flight traces are retained.
   */
  @Test
  void boundedStoreEvictsOldestFirst() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    // Fill exactly to the cap with ids [0, MAX).
    for (long i = 0; i < SpanEnrichmentStates.MAX_TRACES; i++) {
      states.getOrCreate(key(i));
    }
    assertEquals(SpanEnrichmentStates.MAX_TRACES, states.size());
    // One more distinct id evicts the oldest (id 0) and keeps the rest + the new one.
    final long overflow = SpanEnrichmentStates.MAX_TRACES;
    states.getOrCreate(key(overflow));
    assertEquals(SpanEnrichmentStates.MAX_TRACES, states.size(), "size stays at the cap");
    assertNull(states.peek(key(0L)), "oldest entry evicted first (FIFO)");
    assertNotNull(states.peek(key(overflow)), "newest entry retained");
    assertNotNull(states.peek(key(1L)), "second-oldest still present");
  }

  // =====================================================================================
  // reconfiguration: a closing provider must not permanently disable enrichment, nor
  // clobber a newer provider's state
  // =====================================================================================

  /**
   * The core reconfiguration regression: a first gate-on provider shuts down, then a second gate-on
   * provider is created. Enrichment must still work for the second provider. The process-wide
   * interceptor is registered once and rebound, so the second provider is NOT rejected as a
   * duplicate (which would permanently disable enrichment).
   */
  @Test
  void newProviderAfterShutdownStillEnriches() {
    // First provider registers and binds.
    final Provider first =
        new Provider(new Provider.Options(), null, Boolean.TRUE, interceptor -> true);
    assertNotNull(first.spanEnrichmentStates(), "first provider wires a store");
    first.shutdown();
    assertNull(
        SpanEnrichmentInterceptor.INSTANCE.activeStates(),
        "first provider's shutdown leaves the interceptor inert");

    // Second provider is created AFTER the first closed. With the old per-provider interceptor
    // model
    // this registration would be rejected as a duplicate and enrichment would be permanently off.
    final Provider second =
        new Provider(new Provider.Options(), null, Boolean.TRUE, interceptor -> true);
    final SpanEnrichmentStates secondStates = second.spanEnrichmentStates();
    assertNotNull(secondStates, "second provider wires a store");
    assertTrue(
        SpanEnrichmentInterceptor.INSTANCE.activeStates() == secondStates,
        "the interceptor must rebind to the second provider's store");

    // End-to-end: the second provider captures and the interceptor flushes onto the root.
    final long traceId = 0xBEE5L;
    second
        .spanEnrichmentHook()
        .capture(key(traceId), ctx("f", "user-1"), serialDetails("f", 5, false));
    final AgentSpan root = rootSpan(traceId);
    SpanEnrichmentInterceptor.INSTANCE.onTraceComplete(Collections.singletonList(root));
    verify(root).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "BQ=="); // {5} -> 0x05
  }

  /**
   * Overlapping reconfiguration: a second provider rebinds the interceptor while the first is still
   * "open"; when the FIRST provider later shuts down, it must NOT clear the second provider's
   * in-flight state (its unbind is a no-op because it is no longer the active provider).
   */
  @Test
  void lateShutdownOfDisplacedProviderDoesNotClobberActiveProvider() {
    final Provider first =
        new Provider(new Provider.Options(), null, Boolean.TRUE, interceptor -> true);
    final Provider second =
        new Provider(new Provider.Options(), null, Boolean.TRUE, interceptor -> true);
    final SpanEnrichmentStates secondStates = second.spanEnrichmentStates();

    // Second provider is now the active one and has live state.
    final long liveTrace = 0xA11FEL;
    second
        .spanEnrichmentHook()
        .capture(key(liveTrace), ctx("f", "user-1"), serialDetails("f", 9, false));
    assertNotNull(secondStates.peek(key(liveTrace)), "second provider has in-flight state");
    assertTrue(SpanEnrichmentInterceptor.INSTANCE.activeStates() == secondStates);

    // The DISPLACED first provider shuts down late. The active (second) provider must be untouched.
    first.shutdown();
    assertTrue(
        SpanEnrichmentInterceptor.INSTANCE.activeStates() == secondStates,
        "late shutdown of a displaced provider must not unbind the active provider");
    assertNotNull(
        secondStates.peek(key(liveTrace)),
        "late shutdown of a displaced provider must not clear the active provider's state");

    // Sanity: the second provider still flushes correctly.
    final AgentSpan root = rootSpan(liveTrace);
    SpanEnrichmentInterceptor.INSTANCE.onTraceComplete(Collections.singletonList(root));
    verify(root).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "CQ=="); // {9} -> 0x09
  }

  /** Each provider owns a DISTINCT state store, so they never share mutable state. */
  @Test
  void eachProviderOwnsADistinctStateStore() {
    final Provider a =
        new Provider(new Provider.Options(), null, Boolean.TRUE, interceptor -> true);
    final Provider b =
        new Provider(new Provider.Options(), null, Boolean.TRUE, interceptor -> true);
    assertNotNull(a.spanEnrichmentStates());
    assertNotNull(b.spanEnrichmentStates());
    assertTrue(
        a.spanEnrichmentStates() != b.spanEnrichmentStates(),
        "providers must own distinct state stores");

    a.spanEnrichmentStates().getOrCreate(key(1L));
    assertTrue(b.spanEnrichmentStates().isEmpty(), "stores are isolated");
  }

  /**
   * Within ONE provider, the capture hook and the bound interceptor must share the SAME store, so a
   * capture is visible to the flush.
   */
  @Test
  void hookAndInterceptorOfOneProviderShareTheSameStore() {
    final Provider provider =
        new Provider(new Provider.Options(), null, Boolean.TRUE, interceptor -> true);
    final long traceId = 0xBEEFL;
    provider
        .spanEnrichmentHook()
        .capture(key(traceId), ctx("f", "user-1"), serialDetails("f", 9, false));
    assertNotNull(
        SpanEnrichmentInterceptor.INSTANCE.activeStates().peek(key(traceId)),
        "hook capture must be visible in the bound store (same instance)");
  }

  // =====================================================================================
  // 128-bit trace ids that share their low-order 64 bits must not merge
  // =====================================================================================

  /**
   * Two distinct 128-bit trace ids whose low-order 64 bits are identical (so {@code toLong()}
   * collides) must keep SEPARATE accumulators. Keying by {@code toLong()} would merge them; keying
   * by the full hex string keeps them apart.
   */
  @Test
  void distinct128BitTraceIdsSharingLowBitsDoNotMerge() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);
    final SpanEnrichmentInterceptor interceptor = SpanEnrichmentInterceptor.INSTANCE;
    interceptor.bind(states);

    // Same low 64 bits (0xABCD), different high 64 bits — toLong() is identical for both.
    final long lowBits = 0xABCDL;
    final DDTraceId idA = DD128bTraceId.from(0x1111L, lowBits);
    final DDTraceId idB = DD128bTraceId.from(0x2222L, lowBits);
    assertEquals(idA.toLong(), idB.toLong(), "precondition: low 64 bits collide");
    assertTrue(!idA.toHexString().equals(idB.toHexString()), "precondition: full ids differ");

    // Capture distinct serial ids under each full trace id.
    hook.capture(idA.toHexString(), ctx("fa", "user-A"), serialDetails("fa", 100, false));
    hook.capture(idB.toHexString(), ctx("fb", "user-B"), serialDetails("fb", 130, false));

    // They must NOT have merged into one accumulator.
    assertEquals(2, states.size(), "distinct 128-bit traces must not share an accumulator");

    // Flush trace A: only {100} (not {100,130}).
    final AgentSpan rootA = rootSpan(idA);
    interceptor.onTraceComplete(Collections.singletonList(rootA));
    verify(rootA).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "ZA=="); // {100} -> 0x64

    // Flush trace B: only {130}.
    final AgentSpan rootB = rootSpan(idB);
    interceptor.onTraceComplete(Collections.singletonList(rootB));
    verify(rootB).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "ggE="); // {130} -> 0x82 0x01
  }
}
