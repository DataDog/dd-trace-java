package datadog.trace.api.openfeature;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide {@link TraceInterceptor} that writes the accumulated {@code ffe_*} tags onto the
 * local root span when a trace <em>actually completes</em>. This is the WRITE half of the
 * capture-vs-write split — the CAPTURE half is {@link SpanEnrichmentHook}, which fills the active
 * {@link SpanEnrichmentStates} store during flag evaluation.
 *
 * <h2>Reconfiguration safety</h2>
 *
 * <p>The tracer holds interceptors in an add-only, priority-keyed set with no public removal API: a
 * given priority can be occupied by exactly one interceptor for the life of the tracer. If each
 * provider registered its own interceptor at the same priority, the first registration would win
 * forever; after that provider closed, every later gate-on provider would be rejected as a
 * duplicate and enrichment would be permanently disabled.
 *
 * <p>To survive provider close/reopen we therefore register a single, long-lived delegating
 * interceptor ({@link #INSTANCE}) exactly once, and {@linkplain #bind(SpanEnrichmentStates) rebind}
 * it to whichever provider is currently active. A provider {@linkplain
 * #unbind(SpanEnrichmentStates) unbinds} on close. When no provider is bound the interceptor is
 * inert; a fresh provider rebinds it and enrichment resumes — no second registration is ever
 * attempted.
 *
 * <h2>Partial-flush correctness</h2>
 *
 * <p>{@code dd-trace-core} runs {@code onTraceComplete} on <b>every</b> flush, not only on final
 * trace completion: {@code CoreTracer.write(SpanList)} → {@code interceptCompleteTrace(...)} fires
 * for both partial flushes ({@code PendingTrace.partialFlush()} → {@code write(true)}) and the
 * final write ({@code write(false)}). A <b>partial</b> flush deliberately <b>excludes the
 * still-open local root</b> — {@code PendingTrace} holds the root back ({@code rootSpanWritten})
 * and {@code getRootSpan()} returns null on a partial fragment, so {@code CoreTracer.write} does
 * not invoke {@code onRootSpanFinished} for it.
 *
 * <p>Therefore this interceptor flushes+removes state <b>only when the local root span is present
 * in the flushed collection</b> (i.e. this is the final write for the trace). On a partial flush
 * the root is absent, so we return early and <b>keep the accumulator intact</b>, preserving every
 * flag evaluated before the flush boundary. Without this guard, the first partial flush would drain
 * the accumulator and write tags onto a not-yet-finished root, silently dropping all pre-flush
 * enrichment — exactly the long-running-trace data-loss bug.
 *
 * <h2>State ownership</h2>
 *
 * <p>State lives in the per-provider {@link SpanEnrichmentStates} store that is currently bound.
 * {@link #unbind(SpanEnrichmentStates)} clears only the store it unbinds, so one provider's close
 * can never wipe another's in-flight state. The store is weak-keyed by the local-root span, so a
 * trace that never reaches this interceptor is collected with its root and cannot leak unboundedly.
 *
 * <p>All work is wrapped in try/catch — enrichment must NEVER break trace finish.
 */
final class SpanEnrichmentInterceptor implements TraceInterceptor {

  /**
   * Unique priority in the "trace data enrichment" band, after {@code GIT_METADATA} (3) and before
   * the custom-sampling band ({@code Integer.MAX_VALUE - 2}). Distinct from every value in {@code
   * AbstractTraceInterceptor.Priority} and from the CI Visibility interceptors.
   */
  static final int PRIORITY = 4;

  /** The single, long-lived interceptor registered with the tracer (reconfiguration safety). */
  static final SpanEnrichmentInterceptor INSTANCE = new SpanEnrichmentInterceptor();

  // Whether INSTANCE has been accepted by a real tracer. Registration can legitimately fail when
  // the global tracer is still the no-op (not yet installed); in that case we leave this false so a
  // later provider retries. Once true we never re-attempt (the interceptor is in for good).
  private final AtomicBoolean registered = new AtomicBoolean(false);

  // The store of the currently-active provider. null when no gate-on provider is bound, in which
  // case the interceptor is inert. Volatile: the eval/bind threads write, the trace-write thread
  // reads.
  private volatile SpanEnrichmentStates activeStates;

  private SpanEnrichmentInterceptor() {}

  /**
   * Idempotently registers {@link #INSTANCE} with the tracer via {@code registrar}. Safe to call
   * from every gate-on provider: the first successful registration wins and subsequent calls are
   * no-ops. If registration fails because the tracer is not yet installed, a later call retries.
   */
  static void ensureRegistered(final Provider.TraceInterceptorRegistrar registrar) {
    if (INSTANCE.registered.get()) {
      return;
    }
    if (registrar.register(INSTANCE)) {
      INSTANCE.registered.set(true);
    }
  }

  /** Binds the active store to {@code states}, displacing any previously-bound provider. */
  void bind(final SpanEnrichmentStates states) {
    this.activeStates = states;
  }

  /**
   * Unbinds {@code states} if it is still the active store and clears it (cleanup on provider
   * close). If a newer provider has already rebound the interceptor, this is a no-op so the new
   * provider's in-flight state is left untouched.
   */
  void unbind(final SpanEnrichmentStates states) {
    if (this.activeStates == states) {
      this.activeStates = null;
    }
    if (states != null) {
      states.clear();
    }
  }

  /** The currently-bound store, or {@code null} when the interceptor is inert. */
  SpanEnrichmentStates activeStates() {
    return activeStates;
  }

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      final Collection<? extends MutableSpan> trace) {
    try {
      final SpanEnrichmentStates states = this.activeStates;
      if (states == null || trace == null || trace.isEmpty()) {
        return trace;
      }
      // Resolve the local root for this fragment, then require that the root is actually PRESENT in
      // this collection. A partial flush excludes the still-open root, so its absence means "not
      // the
      // final write" — keep the accumulator and bail.
      final MutableSpan localRoot = findLocalRootInFragment(trace);
      if (!(localRoot instanceof AgentSpan)) {
        return trace; // partial flush, or no resolvable in-fragment root: keep state untouched
      }
      // Key by the local-root span object to match the capture-side keying.
      final SpanEnrichmentAccumulator state = states.remove((AgentSpan) localRoot);
      if (state == null || !state.hasData()) {
        return trace;
      }
      for (final Map.Entry<String, String> tag : state.toSpanTags().entrySet()) {
        final String value = tag.getValue();
        if (value != null && !value.isEmpty()) {
          localRoot.setTag(tag.getKey(), value);
        }
      }
    } catch (final Throwable t) {
      // Never let span enrichment break trace finish.
    }
    return trace;
  }

  /**
   * Resolves the local root span for this fragment and returns it ONLY if it is actually present in
   * the fragment by reference identity. Returns {@code null} when the root is not in the collection
   * (a partial flush excludes the still-open root) or when no root can be safely identified.
   *
   * <p>We never guess: a non-root span is never returned. If the first span reports a non-null
   * local root, we accept it only after confirming that exact object is in the fragment; otherwise
   * we look for a span that is provably its own local root and present.
   */
  private static MutableSpan findLocalRootInFragment(
      final Collection<? extends MutableSpan> trace) {
    final MutableSpan first = trace.iterator().next();
    final MutableSpan candidate = first.getLocalRootSpan();
    if (candidate != null) {
      // Accept the reported local root only if it is genuinely part of THIS fragment. On a partial
      // flush the root is reachable by reference but NOT in the collection → reject (keep state).
      for (final MutableSpan span : trace) {
        if (span == candidate) {
          return candidate;
        }
      }
      return null; // root excluded from this fragment → partial flush, do not flush/remove
    }
    // Local root unknown for the first span: only accept a span that is provably its own local root
    // and present here. Never fall back to an arbitrary span.
    for (final MutableSpan span : trace) {
      if (span.getLocalRootSpan() == span) {
        return span;
      }
    }
    return null;
  }

  @Override
  public int priority() {
    return PRIORITY;
  }
}
