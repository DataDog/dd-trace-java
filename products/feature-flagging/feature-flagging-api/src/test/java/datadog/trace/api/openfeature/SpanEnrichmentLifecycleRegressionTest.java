package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.Test;

/**
 * GAP-CLOSURE regression suite for the three BLOCKER lifecycle defects found in the JAVA-01 code
 * review (02-REVIEW-java.md). Each test reproduces a bug the original L0 suite missed because that
 * suite only ever exercised a single root span, single-threaded, one provider, with the root always
 * present in the flushed collection.
 *
 * <ul>
 *   <li><b>CR-01</b> — a PARTIAL flush (child spans only; the still-open root is excluded by
 *       dd-trace-core) must NOT drain the accumulator; pre-flush flags must survive onto the root
 *       at final completion. Verified against {@code CoreTracer.write} →{@code
 *       interceptCompleteTrace} firing on every flush and {@code PendingTrace.write(isPartial)}
 *       holding the root back.
 *   <li><b>CR-02</b> — traces that never reach the interceptor (dropped / Noop / never-finishing)
 *       must not leak accumulator entries unboundedly; the per-provider store is hard-bounded.
 *   <li><b>CR-03</b> — a SECOND gate-on provider whose interceptor registration is rejected
 *       (duplicate priority) must not wire orphan state and its {@code shutdown()} must not clear
 *       the FIRST provider's in-flight state.
 * </ul>
 *
 * <p>Each test is written to FAIL against the pre-fix implementation and PASS after the fix (see
 * the per-test "Fail-before" notes).
 */
class SpanEnrichmentLifecycleRegressionTest {

  // ---- helpers ----

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
  private static AgentSpan rootSpan(final long traceId) {
    final AgentSpan root = mock(AgentSpan.class);
    when(root.getLocalRootSpan()).thenReturn(root);
    when(root.getTraceId()).thenReturn(DDTraceId.from(traceId));
    return root;
  }

  // =====================================================================================
  // CR-01: partial flush must not drop captured state or misattribute tags
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
   *
   * <p><b>Fail-before:</b> the pre-fix interceptor resolved the root by reference via {@code
   * getLocalRootSpan()} (reachable even when absent from the fragment) and unconditionally {@code
   * remove()}d the accumulator on the FIRST (partial) flush — draining {100,108} and writing them
   * onto the not-yet-finished root, so the final flush would only emit {128,130}. The assertion of
   * the full {100,108,128,130} golden vector ("ZAgUAg==") on the final flush, plus "no setTag on
   * the partial flush", both fail pre-fix.
   */
  @Test
  void partialFlushExcludingRootPreservesPreFlushFlags() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);
    final SpanEnrichmentInterceptor interceptor = new SpanEnrichmentInterceptor(states);

    final long traceId = 0x10A6L;
    final AgentSpan root = rootSpan(traceId);

    // (1) pre-partial-flush evaluations
    hook.capture(traceId, ctx("f1", "user-1"), serialDetails("f1", 100, false));
    hook.capture(traceId, ctx("f2", "user-1"), serialDetails("f2", 108, false));

    // (2) PARTIAL flush: a fragment of children only — the open root is NOT in the collection.
    final AgentSpan child1 = childOf(root);
    final AgentSpan child2 = childOf(root);
    final List<MutableSpan> partialFragment = Arrays.asList(child1, child2);
    interceptor.onTraceComplete(partialFragment);

    // No tags may be written on a partial flush, and the accumulator must survive intact.
    verify(child1, never()).setTag(anyString(), anyString());
    verify(child2, never()).setTag(anyString(), anyString());
    verify(root, never()).setTag(anyString(), anyString());
    final SpanEnrichmentAccumulator surviving = states.peek(traceId);
    assertNotNull(surviving, "partial flush must NOT remove the accumulator (CR-01)");
    assertTrue(
        surviving.serialIdsView().contains(100) && surviving.serialIdsView().contains(108),
        "pre-flush serial ids must survive a partial flush (CR-01)");

    // (3) more evaluations after the partial flush
    hook.capture(traceId, ctx("f3", "user-1"), serialDetails("f3", 128, false));
    hook.capture(traceId, ctx("f4", "user-1"), serialDetails("f4", 130, false));

    // (4) FINAL flush: the root is present in the fragment (alongside a late child).
    final AgentSpan lateChild = childOf(root);
    interceptor.onTraceComplete(Arrays.asList(lateChild, root));

    // The full set {100,108,128,130} -> golden "ZAgUAg==" must land on the root.
    verify(root).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "ZAgUAg==");
    assertTrue(states.isEmpty(), "state must be removed only on the final flush (CR-01)");
  }

  /**
   * A partial flush whose first span reports a non-null local root that is absent from the fragment
   * must be treated as "not the final write" and leave state intact — even when there are several
   * children. Guards the WR-02 "never tag a non-root span" concern together with CR-01.
   *
   * <p><b>Fail-before:</b> {@code findLocalRoot} returned {@code first.getLocalRootSpan()} (the
   * absent root) and the interceptor wrote tags onto it / removed state on this partial fragment.
   */
  @Test
  void partialFlushNeverWritesTagsOnAChildSpan() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);
    final SpanEnrichmentInterceptor interceptor = new SpanEnrichmentInterceptor(states);

    final long traceId = 0xC0DEL;
    final AgentSpan root = rootSpan(traceId);
    hook.capture(traceId, ctx("f", "user-1"), serialDetails("f", 7, false));

    final AgentSpan child = childOf(root);
    interceptor.onTraceComplete(Collections.singletonList(child));

    verify(child, never()).setTag(anyString(), anyString());
    verify(root, never()).setTag(anyString(), anyString());
    assertNotNull(states.peek(traceId), "child-only fragment must keep state (CR-01/WR-02)");
  }

  // =====================================================================================
  // CR-02: never-completing traces must not leak accumulator entries unboundedly
  // =====================================================================================

  /**
   * Simulates a sustained stream of traces that each evaluate a flag but never reach the
   * interceptor (dropped traces / Noop tracer / never-finishing roots). The per-provider store must
   * stay bounded by {@link SpanEnrichmentStates#MAX_TRACES} rather than growing without limit.
   *
   * <p><b>Fail-before:</b> state lived in an unbounded {@code static ConcurrentHashMap} with no TTL
   * or cap; the only removal paths were trace-complete and shutdown. Driving 3x the cap distinct,
   * never-flushed trace ids grew the map to 3x the cap — the assertion that size never exceeds the
   * cap fails pre-fix (the #4844 leak class).
   */
  @Test
  void neverCompletingTracesDoNotLeakUnbounded() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);

    final int churn = SpanEnrichmentStates.MAX_TRACES * 3;
    for (int i = 0; i < churn; i++) {
      // Each distinct trace id accumulates once and is NEVER flushed (no interceptor call).
      hook.capture(i, ctx("f", "user-" + i), serialDetails("f", i % 1000 + 1, false));
      assertTrue(
          states.size() <= SpanEnrichmentStates.MAX_TRACES,
          "state store must stay bounded for never-completing traces (CR-02)");
    }
    assertEquals(
        SpanEnrichmentStates.MAX_TRACES,
        states.size(),
        "store saturates at the cap, never beyond (CR-02)");
  }

  /**
   * Bounding is FIFO by insertion: once the cap is reached, the oldest entries are evicted so the
   * newest in-flight traces are retained. Proves eviction targets the leak (oldest, likely
   * abandoned) rather than the live tail.
   *
   * <p><b>Fail-before:</b> no eviction existed at all, so this behavior was absent.
   */
  @Test
  void boundedStoreEvictsOldestFirst() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    // Fill exactly to the cap with ids [0, MAX).
    for (long i = 0; i < SpanEnrichmentStates.MAX_TRACES; i++) {
      states.getOrCreate(i);
    }
    assertEquals(SpanEnrichmentStates.MAX_TRACES, states.size());
    // One more distinct id evicts the oldest (id 0) and keeps the rest + the new one.
    final long overflowKey = SpanEnrichmentStates.MAX_TRACES;
    states.getOrCreate(overflowKey);
    assertEquals(SpanEnrichmentStates.MAX_TRACES, states.size(), "size stays at the cap");
    assertNull(states.peek(0L), "oldest entry evicted first (CR-02 FIFO)");
    assertNotNull(states.peek(overflowKey), "newest entry retained");
    assertNotNull(states.peek(1L), "second-oldest still present");
  }

  // =====================================================================================
  // CR-03: a rejected second provider must not corrupt the first provider's state
  // =====================================================================================

  /**
   * Models reconfiguration: a first gate-on provider registers successfully; a SECOND gate-on
   * provider's interceptor registration is REJECTED (duplicate priority 4 — what {@code
   * CoreTracer.addTraceInterceptor} returns false for). The second provider must wire NO hook /
   * interceptor, and its {@code shutdown()} must leave the first provider's in-flight state intact.
   *
   * <p><b>Fail-before:</b> the {@code addTraceInterceptor} return value was ignored, so the second
   * provider kept a non-null hook+interceptor and shared the same global {@code STATES}; its {@code
   * shutdown()} called {@code STATES.clear()}, wiping the first provider's live per-trace state.
   * The assertions "second provider has null hook/interceptor" and "first provider's state survives
   * the second's shutdown" both fail pre-fix.
   */
  @Test
  void secondProviderRejectedRegistrationDoesNotClearFirstProviderState() {
    // First provider: registration succeeds.
    final Provider first =
        new Provider(new Provider.Options(), null, Boolean.TRUE, interceptor -> true);
    assertNotNull(first.spanEnrichmentInterceptor(), "first provider registers");
    assertNotNull(first.spanEnrichmentHook(), "first provider wires a hook");

    // First provider captures live state for an in-flight trace.
    final SpanEnrichmentStates firstStates = first.spanEnrichmentInterceptor().states();
    final long liveTrace = 0xA11FEL;
    first.spanEnrichmentHook().capture(liveTrace, ctx("f", "user-1"), serialDetails("f", 5, true));
    assertNotNull(firstStates.peek(liveTrace), "first provider has in-flight state");

    // Second provider: registration is REJECTED (duplicate). It must wire nothing.
    final Provider second =
        new Provider(new Provider.Options(), null, Boolean.TRUE, interceptor -> false);
    assertNull(
        second.spanEnrichmentInterceptor(),
        "rejected registration => no interceptor on the second provider (CR-03)");
    assertNull(
        second.spanEnrichmentHook(),
        "rejected registration => no orphan hook on the second provider (CR-03)");

    // Second provider shuts down. The first provider's live state MUST be untouched.
    second.shutdown();
    assertNotNull(
        firstStates.peek(liveTrace),
        "second provider's shutdown must NOT clear the first provider's state (CR-03)");
    assertTrue(
        first.spanEnrichmentInterceptor().isEnabled(),
        "first provider's interceptor must remain enabled after the second's shutdown (CR-03)");

    // Sanity: the first provider can still flush its own state correctly afterward.
    final AgentSpan root = rootSpan(liveTrace);
    first.spanEnrichmentInterceptor().onTraceComplete(Collections.singletonList(root));
    verify(root)
        .setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "BQ=="); // {5} -> delta {5} -> 0x05
    assertTrue(firstStates.isEmpty(), "first provider flushes + clears its own state");
  }

  /**
   * The two providers must own DISTINCT state stores — the structural guarantee behind CR-03. Even
   * when both register successfully (independent tracers / test seam), one's store is never the
   * other's.
   *
   * <p><b>Fail-before:</b> both providers shared the single global static {@code STATES}, so this
   * "distinct instance" guarantee did not hold.
   */
  @Test
  void eachProviderOwnsADistinctStateStore() {
    final Provider a =
        new Provider(new Provider.Options(), null, Boolean.TRUE, interceptor -> true);
    final Provider b =
        new Provider(new Provider.Options(), null, Boolean.TRUE, interceptor -> true);
    assertNotNull(a.spanEnrichmentInterceptor());
    assertNotNull(b.spanEnrichmentInterceptor());
    assertFalse(
        a.spanEnrichmentInterceptor().states() == b.spanEnrichmentInterceptor().states(),
        "providers must own distinct state stores (CR-03)");

    // Mutating a's store does not affect b's.
    a.spanEnrichmentInterceptor().states().getOrCreate(1L);
    assertTrue(b.spanEnrichmentInterceptor().states().isEmpty(), "stores are isolated");
  }

  /**
   * Within ONE provider, the capture hook and the write interceptor must share the SAME store, so a
   * capture is visible to the flush. (The cross-thread capture→flush handoff depends on this.)
   */
  @Test
  void hookAndInterceptorOfOneProviderShareTheSameStore() {
    final Provider provider =
        new Provider(new Provider.Options(), null, Boolean.TRUE, interceptor -> true);
    final long traceId = 0xBEEFL;
    provider
        .spanEnrichmentHook()
        .capture(traceId, ctx("f", "user-1"), serialDetails("f", 9, false));
    // The interceptor's store must see what the hook captured (same instance).
    assertNotNull(
        provider.spanEnrichmentInterceptor().states().peek(traceId),
        "hook capture must be visible in the interceptor's store (same instance)");
  }
}
