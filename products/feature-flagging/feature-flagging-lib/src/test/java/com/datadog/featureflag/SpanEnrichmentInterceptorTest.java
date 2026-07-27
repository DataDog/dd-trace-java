package com.datadog.featureflag;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Write-tier interceptor suite: partial-flush correctness (the long-running-trace regression),
 * identity keying, and the no-state fast path. dd-trace-core runs {@code onTraceComplete} on every
 * flush; a partial flush excludes the still-open root, so the interceptor must only flush+remove
 * when the local root is present in the fragment.
 */
class SpanEnrichmentInterceptorTest {

  /** A mock local-root span reporting itself as its own local root. */
  private static AgentSpan rootSpan() {
    final AgentSpan root = mock(AgentSpan.class);
    when(root.getLocalRootSpan()).thenReturn(root);
    return root;
  }

  /** A mock child span whose local root is {@code root} but which is itself NOT the root. */
  private static AgentSpan childOf(final AgentSpan root) {
    final AgentSpan child = mock(AgentSpan.class);
    when(child.getLocalRootSpan()).thenReturn(root);
    return child;
  }

  @Test
  void priorityIsUnique() {
    assertEquals(4, new SpanEnrichmentInterceptor(new SpanEnrichmentStates()).priority());
  }

  @Test
  void emptyOrNoStateIsANoOpFastPath() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentInterceptor interceptor = new SpanEnrichmentInterceptor(states);
    // empty trace
    assertTrue(interceptor.onTraceComplete(Collections.emptyList()).isEmpty());
    // no accumulated state => fast path, never touches the span
    final AgentSpan root = rootSpan();
    interceptor.onTraceComplete(Collections.singletonList(root));
    verify(root, never()).setTag(anyString(), anyString());
  }

  @Test
  void finalFlushWithRootPresentWritesTags() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentInterceptor interceptor = new SpanEnrichmentInterceptor(states);
    final AgentSpan root = rootSpan();
    states.getOrCreate(root).addSerialId(5);

    interceptor.onTraceComplete(Collections.singletonList(root));

    verify(root).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "BQ=="); // {5} -> 0x05
    assertTrue(states.isEmpty(), "state must be removed on the final flush");
  }

  @Test
  void partialFlushExcludingRootPreservesState() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentInterceptor interceptor = new SpanEnrichmentInterceptor(states);
    final AgentSpan root = rootSpan();
    states.getOrCreate(root).addSerialId(100);
    states.getOrCreate(root).addSerialId(108);

    // PARTIAL flush: a fragment of children only — the open root is NOT in the collection.
    final AgentSpan child1 = childOf(root);
    final AgentSpan child2 = childOf(root);
    interceptor.onTraceComplete(Arrays.asList(child1, child2));

    verify(child1, never()).setTag(anyString(), anyString());
    verify(child2, never()).setTag(anyString(), anyString());
    verify(root, never()).setTag(anyString(), anyString());
    final SpanEnrichmentAccumulator surviving = states.peek(root);
    assertNotNull(surviving, "partial flush must NOT remove the accumulator");
    assertTrue(surviving.serialIdsView().contains(100) && surviving.serialIdsView().contains(108));

    // more evaluations, then the FINAL flush with the root present
    states.getOrCreate(root).addSerialId(128);
    states.getOrCreate(root).addSerialId(130);
    final AgentSpan lateChild = childOf(root);
    interceptor.onTraceComplete(Arrays.asList(lateChild, root));

    verify(root).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "ZAgUAg=="); // {100,108,128,130}
    assertTrue(states.isEmpty(), "state removed only on the final flush");
  }

  @Test
  void childOnlyFragmentNeverTagsAChildSpan() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentInterceptor interceptor = new SpanEnrichmentInterceptor(states);
    final AgentSpan root = rootSpan();
    states.getOrCreate(root).addSerialId(7);

    final AgentSpan child = childOf(root);
    interceptor.onTraceComplete(Collections.singletonList(child));

    verify(child, never()).setTag(anyString(), anyString());
    verify(root, never()).setTag(anyString(), anyString());
    assertNotNull(states.peek(root), "child-only fragment must keep state");
  }

  @Test
  void distinctTracesDoNotMerge() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentInterceptor interceptor = new SpanEnrichmentInterceptor(states);
    final AgentSpan rootA = rootSpan();
    final AgentSpan rootB = rootSpan();
    states.getOrCreate(rootA).addSerialId(100);
    states.getOrCreate(rootB).addSerialId(130);
    assertEquals(2, states.size(), "distinct root spans must not share an accumulator");

    interceptor.onTraceComplete(Collections.singletonList(rootA));
    verify(rootA).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "ZA=="); // {100} -> 0x64
    interceptor.onTraceComplete(Collections.singletonList(rootB));
    verify(rootB).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "ggE="); // {130} -> 0x82 0x01
  }

  @Test
  void neverCompletingTracesAreNotCapped() {
    // No cap / eviction: while their local-root spans are reachable, every never-completing trace
    // keeps its accumulator. Weak-key reclamation (not a fixed count) bounds memory.
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final int churn = 20_000; // well beyond the old 4096 cap
    final List<AgentSpan> liveRoots = new ArrayList<>(churn);
    for (int i = 0; i < churn; i++) {
      final AgentSpan root = rootSpan();
      liveRoots.add(root);
      states.getOrCreate(root).addSerialId(i % 1000 + 1);
    }
    assertEquals(churn, states.size(), "no cap/eviction: every live root keeps its accumulator");
  }

  @Test
  void nullTraceIsANoOp() {
    final SpanEnrichmentInterceptor interceptor =
        new SpanEnrichmentInterceptor(new SpanEnrichmentStates());
    assertNull(interceptor.onTraceComplete(null));
  }

  @Test
  void rootPresentButNoAccumulatorEntryWritesNothing() {
    // states is non-empty (has rootA) but we flush rootB which has no entry → remove returns null.
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentInterceptor interceptor = new SpanEnrichmentInterceptor(states);
    final AgentSpan rootA = rootSpan();
    states.getOrCreate(rootA).addSerialId(1);
    final AgentSpan rootB = rootSpan();
    interceptor.onTraceComplete(Collections.singletonList(rootB));
    verify(rootB, never()).setTag(anyString(), anyString());
  }

  @Test
  void emptyAccumulatorWritesNoTags() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentInterceptor interceptor = new SpanEnrichmentInterceptor(states);
    final AgentSpan root = rootSpan();
    states.getOrCreate(root); // entry exists but has no data
    interceptor.onTraceComplete(Collections.singletonList(root));
    verify(root, never()).setTag(anyString(), anyString());
    assertTrue(states.isEmpty(), "empty accumulator is still removed on the final flush");
  }

  @Test
  void fallbackResolvesSelfRootWhenFirstSpanReportsNoLocalRoot() {
    // First span reports a null local root; a later span that is its own local root is chosen.
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentInterceptor interceptor = new SpanEnrichmentInterceptor(states);
    final AgentSpan noRootFirst = mock(AgentSpan.class);
    when(noRootFirst.getLocalRootSpan()).thenReturn(null);
    final AgentSpan root = rootSpan();
    states.getOrCreate(root).addSerialId(5);
    interceptor.onTraceComplete(Arrays.<MutableSpan>asList(noRootFirst, root));
    verify(root).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "BQ==");
  }

  @Test
  void neverThrowsWhenSetTagFails() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentInterceptor interceptor = new SpanEnrichmentInterceptor(states);
    final AgentSpan root = rootSpan();
    when(root.setTag(anyString(), anyString())).thenThrow(new RuntimeException("boom"));
    states.getOrCreate(root).addSerialId(5);
    // Must swallow the exception — enrichment can never break trace finish.
    interceptor.onTraceComplete(Collections.singletonList(root));
  }

  @Test
  void keyingSymmetryRootResolvedFromFragmentMatchesCapturedRoot() {
    final SpanEnrichmentStates states = new SpanEnrichmentStates();
    final SpanEnrichmentInterceptor interceptor = new SpanEnrichmentInterceptor(states);
    final AgentSpan root = rootSpan();
    states.getOrCreate(root).addSerialId(5);

    // The interceptor resolves the root from the fragment (root + a late child); it must be the
    // same object so the remove hits the captured accumulator.
    final AgentSpan child = childOf(root);
    interceptor.onTraceComplete(Arrays.<MutableSpan>asList(child, root));
    verify(root).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "BQ=="); // {5} -> 0x05
    assertTrue(states.isEmpty());
  }
}
