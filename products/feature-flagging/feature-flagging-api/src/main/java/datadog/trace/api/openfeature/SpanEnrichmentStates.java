package datadog.trace.api.openfeature;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Instance-owned store of per-trace {@link SpanEnrichmentAccumulator} state, keyed by the
 * local-root span object.
 *
 * <p><b>Weak keys.</b> The map is a {@link WeakHashMap} keyed by the local-root {@link AgentSpan}
 * instance. The accumulator is reachable only while its local-root span is, so a trace that never
 * reaches the interceptor (dropped, Noop tracer, never-finishing root) is collected together with
 * its root — it cannot leak unboundedly. No cap, FIFO eviction, or cleanup thread is required;
 * {@code WeakHashMap} purges stale entries on access.
 *
 * <p><b>Identity keying.</b> {@code DDSpan} does not override {@code equals}/{@code hashCode}, so
 * the map keys by object identity. Keying by the local-root span object (rather than the 128-bit
 * trace-id hex string) is inherently unique per trace: two distinct traces have distinct root
 * objects and can never share an accumulator.
 *
 * <p>Each {@link SpanEnrichmentHook}/{@link SpanEnrichmentInterceptor} pair shares one store
 * instance. Because the store is instance-owned rather than a shared static, one provider's cleanup
 * clears only its own state and can never wipe another (still-active) provider's in-flight entries.
 *
 * <p>Thread-safety: all access is guarded by the intrinsic lock on this instance. The hook writes
 * (eval thread) and the interceptor reads+removes (trace-write thread) concurrently, so every
 * mutator and accessor synchronizes. Contention is low — operations are O(1) map touches.
 */
final class SpanEnrichmentStates {

  // Weak keys: the accumulator is GC'd with its local-root span, so a trace that never completes
  // cannot leak. Identity-keyed by the local-root AgentSpan object. guarded by 'this'.
  private final Map<AgentSpan, SpanEnrichmentAccumulator> states = new WeakHashMap<>();

  /** Returns the accumulator for {@code root}, creating (and inserting) it if absent. */
  synchronized SpanEnrichmentAccumulator getOrCreate(final AgentSpan root) {
    SpanEnrichmentAccumulator existing = states.get(root);
    if (existing != null) {
      return existing;
    }
    final SpanEnrichmentAccumulator created = new SpanEnrichmentAccumulator();
    states.put(root, created);
    return created;
  }

  /** Removes and returns the accumulator for {@code root}, or {@code null} if absent. */
  synchronized SpanEnrichmentAccumulator remove(final AgentSpan root) {
    return states.remove(root);
  }

  /** Clears all tracked state (cleanup on provider close / unbind). */
  synchronized void clear() {
    states.clear();
  }

  synchronized int size() {
    return states.size();
  }

  synchronized boolean isEmpty() {
    return states.isEmpty();
  }

  // ---- test-only accessor ----

  synchronized SpanEnrichmentAccumulator peek(final AgentSpan root) {
    return states.get(root);
  }
}
