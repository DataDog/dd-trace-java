package datadog.trace.api.openfeature;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
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
 *       must not leak accumulator entries unboundedly; the store is weak-keyed by the local-root
 *       span, so entries are collected once their root becomes unreachable (no cap / eviction).
 *   <li>reconfiguration — after a provider closes, a new gate-on provider must still enrich (the
 *       process-wide interceptor rebinds), and a closing provider must never clobber a newer
 *       provider's in-flight state.
 *   <li>distinct traces — two distinct traces (distinct local-root span objects) must not merge
 *       enrichment state.
 *   <li>keying symmetry — the root captured by the hook and the root resolved by the interceptor
 *       from the flushed fragment must be the SAME object, so identity keying round-trips.
 * </ul>
 */
class SpanEnrichmentLifecycleRegressionTest {

  @AfterEach
  void resetInterceptor() {
    // The interceptor is a process-wide singleton; leave it inert for the next test.
    SpanEnrichmentInterceptor.INSTANCE.unbind(SpanEnrichmentInterceptor.INSTANCE.activeStates());
  }

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

  /** A mock local-root span reporting itself as its own local root. */
  private static AgentSpan rootSpan() {
    final AgentSpan root = mock(AgentSpan.class);
    when(root.getLocalRootSpan()).thenReturn(root);
    return root;
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

    final AgentSpan root = rootSpan();

    // (1) pre-partial-flush evaluations
    hook.capture(root, ctx("f1", "user-1"), serialDetails("f1", 100, false));
    hook.capture(root, ctx("f2", "user-1"), serialDetails("f2", 108, false));

    // (2) PARTIAL flush: a fragment of children only — the open root is NOT in the collection.
    final AgentSpan child1 = childOf(root);
    final AgentSpan child2 = childOf(root);
    final List<MutableSpan> partialFragment = Arrays.asList(child1, child2);
    interceptor.onTraceComplete(partialFragment);

    // No tags may be written on a partial flush, and the accumulator must survive intact.
    verify(child1, never()).setTag(anyString(), anyString());
    verify(child2, never()).setTag(anyString(), anyString());
    verify(root, never()).setTag(anyString(), anyString());
    final SpanEnrichmentAccumulator surviving = states.peek(root);
    assertNotNull(surviving, "partial flush must NOT remove the accumulator");
    assertTrue(
        surviving.serialIdsView().contains(100) && surviving.serialIdsView().contains(108),
        "pre-flush serial ids must survive a partial flush");

    // (3) more evaluations after the partial flush
    hook.capture(root, ctx("f3", "user-1"), serialDetails("f3", 128, false));
    hook.capture(root, ctx("f4", "user-1"), serialDetails("f4", 130, false));

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

    final AgentSpan root = rootSpan();
    hook.capture(root, ctx("f", "user-1"), serialDetails("f", 7, false));

    final AgentSpan child = childOf(root);
    interceptor.onTraceComplete(Collections.singletonList(child));

    verify(child, never()).setTag(anyString(), anyString());
    verify(root, never()).setTag(anyString(), anyString());
    assertNotNull(states.peek(root), "child-only fragment must keep state");
  }

  // =====================================================================================
  // never-completing traces must not leak accumulator entries unboundedly
  // =====================================================================================

  /**
   * There is no cap and no eviction: while their local-root spans are still reachable, every
   * never-completing trace keeps its own accumulator. The weak-keyed store bounds memory by
   * reachability (see {@link #unreachableRootStateIsWeaklyCollected()}), not by a fixed count, so
   * inserting far more distinct roots than the old 4096 cap never drops a still-live entry.
   */
  @Test
  void neverCompletingTracesAreNotCapped() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);

    final int churn = 20_000; // well beyond the old 4096 cap
    // Hold strong references so nothing is GC'd during the test — proves there is no size cap.
    final List<AgentSpan> liveRoots = new ArrayList<>(churn);
    for (int i = 0; i < churn; i++) {
      final AgentSpan root = rootSpan();
      liveRoots.add(root);
      hook.capture(root, ctx("f", "user-" + i), serialDetails("f", i % 1000 + 1, false));
    }
    assertEquals(churn, states.size(), "no cap/eviction: every live root keeps its accumulator");
  }

  /**
   * Weak-reference semantics: once a never-completing trace's local-root span becomes unreachable,
   * its accumulator entry is collected — the store cannot leak unboundedly without any cap. GC is
   * non-deterministic, so this polls with explicit {@code System.gc()} hints until the entry
   * clears.
   */
  @Test
  void unreachableRootStateIsWeaklyCollected() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);

    AgentSpan root = rootSpan();
    hook.capture(root, ctx("f", "user-1"), serialDetails("f", 1, false));
    assertEquals(1, states.size(), "one in-flight accumulator before the root is dropped");

    final WeakReference<AgentSpan> ref = new WeakReference<>(root);
    root = null; // drop the only strong reference to the never-completing root

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> {
              System.gc();
              assertNull(ref.get(), "unreferenced root must be collectable");
              assertEquals(0, states.size(), "accumulator must be purged with its collected root");
            });
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
    final AgentSpan root = rootSpan();
    second.spanEnrichmentHook().capture(root, ctx("f", "user-1"), serialDetails("f", 5, false));
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
    final AgentSpan root = rootSpan();
    second.spanEnrichmentHook().capture(root, ctx("f", "user-1"), serialDetails("f", 9, false));
    assertNotNull(secondStates.peek(root), "second provider has in-flight state");
    assertTrue(SpanEnrichmentInterceptor.INSTANCE.activeStates() == secondStates);

    // The DISPLACED first provider shuts down late. The active (second) provider must be untouched.
    first.shutdown();
    assertTrue(
        SpanEnrichmentInterceptor.INSTANCE.activeStates() == secondStates,
        "late shutdown of a displaced provider must not unbind the active provider");
    assertNotNull(
        secondStates.peek(root),
        "late shutdown of a displaced provider must not clear the active provider's state");

    // Sanity: the second provider still flushes correctly.
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

    a.spanEnrichmentStates().getOrCreate(rootSpan());
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
    final AgentSpan root = rootSpan();
    provider.spanEnrichmentHook().capture(root, ctx("f", "user-1"), serialDetails("f", 9, false));
    assertNotNull(
        SpanEnrichmentInterceptor.INSTANCE.activeStates().peek(root),
        "hook capture must be visible in the bound store (same instance)");
  }

  // =====================================================================================
  // distinct traces (distinct local-root span objects) must not merge
  // =====================================================================================

  /**
   * Two distinct traces have two distinct local-root span objects, so identity keying keeps their
   * accumulators SEPARATE regardless of any trace-id bits. (Under the old hex-string keying this
   * guarded against 128-bit ids sharing their low-order 64 bits; identity keying makes it
   * inherent.)
   */
  @Test
  void distinctTracesDoNotMerge() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(states);
    final SpanEnrichmentInterceptor interceptor = SpanEnrichmentInterceptor.INSTANCE;
    interceptor.bind(states);

    final AgentSpan rootA = rootSpan();
    final AgentSpan rootB = rootSpan();

    // Capture distinct serial ids under each distinct root.
    hook.capture(rootA, ctx("fa", "user-A"), serialDetails("fa", 100, false));
    hook.capture(rootB, ctx("fb", "user-B"), serialDetails("fb", 130, false));

    // They must NOT have merged into one accumulator.
    assertEquals(2, states.size(), "distinct root spans must not share an accumulator");

    // Flush trace A: only {100} (not {100,130}).
    interceptor.onTraceComplete(Collections.singletonList(rootA));
    verify(rootA).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "ZA=="); // {100} -> 0x64

    // Flush trace B: only {130}.
    interceptor.onTraceComplete(Collections.singletonList(rootB));
    verify(rootB).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "ggE="); // {130} -> 0x82 0x01
  }

  // =====================================================================================
  // keying symmetry: the hook's captured root and the interceptor's resolved root are identical
  // =====================================================================================

  /**
   * The whole identity-keying design rests on the hook and the interceptor referencing the SAME
   * local-root span object. The hook captures against the root resolved by its {@code
   * RootSpanResolver}; the interceptor resolves the root from the flushed fragment via {@code
   * findLocalRootInFragment}. This asserts both resolve to the same instance so the accumulator
   * captured on the eval side is exactly the one removed and flushed on the write side.
   */
  @Test
  void hookCapturedRootAndInterceptorResolvedRootAreSameObject() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final AgentSpan root = rootSpan();
    // The hook resolves its root via the injected resolver (the production DEFAULT_RESOLVER calls
    // activeSpan().getLocalRootSpan(); here we inject the same object the fragment will report).
    final SpanEnrichmentHook hook = new SpanEnrichmentHook(() -> root, states);
    final SpanEnrichmentInterceptor interceptor = SpanEnrichmentInterceptor.INSTANCE;
    interceptor.bind(states);

    hook.finallyAfter(
        ctx("f", "user-1"), serialDetails("f", 5, false), Collections.<String, Object>emptyMap());
    final SpanEnrichmentAccumulator captured = states.peek(root);
    assertNotNull(captured, "hook captures against the resolved local root");

    // The interceptor resolves the root from the fragment; it must be the same object, so the
    // remove hits the captured accumulator and flushes it onto that exact root.
    final AgentSpan child = childOf(root);
    interceptor.onTraceComplete(Arrays.asList(child, root));
    verify(root).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "BQ=="); // {5} -> 0x05
    assertTrue(states.isEmpty(), "the interceptor removed the same accumulator the hook created");
  }
}
