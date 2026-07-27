package com.datadog.featureflag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.featureflag.SpanEnrichmentEvent;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * Write-tier listener suite: the agent-side {@link SpanEnrichmentWriter} translates {@link
 * SpanEnrichmentEvent}s from the flag-eval seam into per-local-root accumulator state (resolving
 * the active root through an injectable resolver so no static tracer is needed).
 */
class SpanEnrichmentWriterTest {

  private static AgentSpan rootSpan() {
    final AgentSpan root = mock(AgentSpan.class);
    when(root.getLocalRootSpan()).thenReturn(root);
    return root;
  }

  private static SpanEnrichmentWriter writerFor(final AgentSpan root) {
    return new SpanEnrichmentWriter(() -> root);
  }

  @Test
  void serialIdWithDoLogAndTargetingKeyRecordsSerialAndSubject() {
    final AgentSpan root = rootSpan();
    final SpanEnrichmentWriter writer = writerFor(root);
    writer.accept(SpanEnrichmentEvent.serialId(42, true, "user-1"));

    final SpanEnrichmentAccumulator state = writer.states().peek(root);
    assertNotNull(state);
    assertTrue(state.serialIdsView().contains(42));
    assertEquals(1, state.subjectCount(), "doLog=true + targeting key => subject recorded");
  }

  @Test
  void serialIdWithoutDoLogRecordsNoSubject() {
    final AgentSpan root = rootSpan();
    final SpanEnrichmentWriter writer = writerFor(root);
    writer.accept(SpanEnrichmentEvent.serialId(7, false, "user-1"));

    final SpanEnrichmentAccumulator state = writer.states().peek(root);
    assertTrue(state.serialIdsView().contains(7));
    assertEquals(0, state.subjectCount(), "doLog=false must not record a subject");
  }

  @Test
  void serialIdWithNullTargetingKeyRecordsNoSubject() {
    final AgentSpan root = rootSpan();
    final SpanEnrichmentWriter writer = writerFor(root);
    writer.accept(SpanEnrichmentEvent.serialId(9, true, null));

    final SpanEnrichmentAccumulator state = writer.states().peek(root);
    assertTrue(state.serialIdsView().contains(9));
    assertEquals(0, state.subjectCount(), "no targeting key => no subject");
  }

  @Test
  void runtimeDefaultRecordsDefault() {
    final AgentSpan root = rootSpan();
    final SpanEnrichmentWriter writer = writerFor(root);
    writer.accept(SpanEnrichmentEvent.runtimeDefault("flag", Collections.singletonMap("k", "v")));

    final SpanEnrichmentAccumulator state = writer.states().peek(root);
    assertNotNull(state);
    assertEquals(1, state.defaultCount());
  }

  @Test
  void noActiveSpanAccumulatesNothing() {
    final SpanEnrichmentWriter writer = new SpanEnrichmentWriter(() -> null);
    writer.accept(SpanEnrichmentEvent.serialId(1, true, "user-1"));
    assertTrue(writer.states().isEmpty(), "no active span => no accumulator state");
  }

  @Test
  void nullEventIsANoOp() {
    final AgentSpan root = rootSpan();
    final SpanEnrichmentWriter writer = writerFor(root);
    writer.accept(null);
    assertTrue(writer.states().isEmpty());
  }

  @Test
  void endToEndAccumulateThenFlush() {
    final AgentSpan root = rootSpan();
    final SpanEnrichmentWriter writer = writerFor(root);
    writer.accept(SpanEnrichmentEvent.serialId(5, false, null));

    writer.interceptor().onTraceComplete(Collections.singletonList(root));
    verify(root).setTag(SpanEnrichmentAccumulator.TAG_FLAGS_ENC, "BQ=="); // {5} -> 0x05
    assertTrue(writer.states().isEmpty(), "flush removes the accumulated state");
  }

  @Test
  void doesNotAccumulateWhenInterceptorCannotRegister() {
    final AgentSpan root = rootSpan();
    // Registrar always rejects → nothing would ever flush the state, so we must not accumulate.
    final SpanEnrichmentWriter writer = new SpanEnrichmentWriter(() -> root, interceptor -> false);
    writer.accept(SpanEnrichmentEvent.serialId(5, false, null));
    assertTrue(
        writer.states().isEmpty(), "no accumulation when the interceptor cannot be registered");
  }

  @Test
  void agentSingletonIsStableAcrossRestarts() {
    // FeatureFlaggingSystem reuses this one instance across start/stop, so the (unremovable) trace
    // interceptor is registered exactly once and never re-registered on restart.
    final SpanEnrichmentWriter first = SpanEnrichmentWriter.getInstance();
    final SpanEnrichmentWriter second = SpanEnrichmentWriter.getInstance();
    assertSame(first, second, "agent wiring must reuse one writer across restarts");
    assertSame(first.interceptor(), second.interceptor(), "same interceptor across restarts");
    assertSame(first.states(), second.states(), "same state store across restarts");
  }

  @Test
  void resolveLocalRootLogic() {
    assertNull(SpanEnrichmentWriter.resolveLocalRoot(null));

    final AgentSpan root = rootSpan(); // reports itself as its own local root
    assertSame(root, SpanEnrichmentWriter.resolveLocalRoot(root));

    final AgentSpan child = mock(AgentSpan.class);
    final AgentSpan childRoot = rootSpan();
    when(child.getLocalRootSpan()).thenReturn(childRoot);
    assertSame(childRoot, SpanEnrichmentWriter.resolveLocalRoot(child));

    final AgentSpan noLocal = mock(AgentSpan.class);
    when(noLocal.getLocalRootSpan()).thenReturn(null);
    assertSame(
        noLocal, SpanEnrichmentWriter.resolveLocalRoot(noLocal), "no local root → active span");
  }

  @Test
  void initSubscribesAndCloseClearsState() {
    final AgentSpan root = rootSpan();
    final SpanEnrichmentWriter writer = writerFor(root);
    writer.init();
    try {
      writer.accept(SpanEnrichmentEvent.serialId(5, false, null));
      assertNotNull(writer.states().peek(root));
    } finally {
      writer.close();
    }
    assertTrue(writer.states().isEmpty(), "close() clears accumulated state");
  }

  @Test
  void runtimeDefaultWithNullFlagKeyIsIgnored() {
    final AgentSpan root = rootSpan();
    final SpanEnrichmentWriter writer = writerFor(root);
    writer.accept(SpanEnrichmentEvent.runtimeDefault(null, "v"));
    // No serial id and null flag key → nothing recorded (getOrCreate ran, but no data added).
    final SpanEnrichmentAccumulator state = writer.states().peek(root);
    assertTrue(state == null || !state.hasData());
  }

  @Test
  void acceptSwallowsResolverErrors() {
    final SpanEnrichmentWriter writer =
        new SpanEnrichmentWriter(
            () -> {
              throw new RuntimeException("resolver boom");
            });
    // Must not propagate — enrichment can never break flag evaluation.
    writer.accept(SpanEnrichmentEvent.serialId(5, false, null));
    assertTrue(writer.states().isEmpty());
  }

  @Test
  void registrarErrorIsSwallowedAndNotLatched() {
    final AgentSpan root = rootSpan();
    final SpanEnrichmentWriter writer =
        new SpanEnrichmentWriter(
            () -> root,
            interceptor -> {
              throw new RuntimeException("register boom");
            });
    // Registration throws → swallowed, not latched, and nothing accumulates (never flushable).
    writer.accept(SpanEnrichmentEvent.serialId(5, false, null));
    assertTrue(writer.states().isEmpty());
  }

  @Test
  void distinctEventsUnderSameRootShareAccumulator() {
    final AgentSpan root = rootSpan();
    final SpanEnrichmentWriter writer = writerFor(root);
    writer.accept(SpanEnrichmentEvent.serialId(100, false, null));
    writer.accept(SpanEnrichmentEvent.serialId(108, false, null));
    assertEquals(1, writer.states().size(), "same root => one shared accumulator");
    verify(root, never()).setTag(anyString(), anyString());
  }
}
