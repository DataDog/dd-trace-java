package datadog.trace.common.writer.ddagent;

import datadog.common.queue.AdmissionBudget;

/**
 * Bounds the spans the writer holds between publish and serialization.
 *
 * <p>The writer's queues are bounded by trace count, which says nothing about memory: a stalled
 * agent leaves them full of traces of any size. This budget counts spans instead, shared across
 * every writer queue. It is charged when a trace is enqueued and released once the serializer has
 * consumed the trace, or when the trace is dropped after admission.
 *
 * <p>Each kind of trace gets its own cutoff, so the least valuable shed first as usage rises:
 *
 * <ul>
 *   <li>sampled-out traces, which only feed agent-side stats or single span sampling: 1/4 of the
 *       limit;
 *   <li>kept traces: from 1/2 of the limit for a single span, rising linearly to the full limit at
 *       {@link #LARGE_CHUNK_SPANS} spans. Under pressure a large trace wins over small ones; small
 *       traces are plentiful and interchangeable, a large one is usually the only one of its kind.
 *       Stats are computed before the writer, so a shed trace costs detail, not stats;
 *   <li>force-kept traces: twice the limit.
 * </ul>
 *
 * <p>Admission checks headroom, not fit (see {@link AdmissionBudget}), so a large trace is never
 * refused for its size alone. Partial flush bounds chunk size, which in turn bounds the overshoot.
 */
public final class SpanAdmission {
  /** Chunk size at which a kept trace is admitted up to the full limit. */
  static final int LARGE_CHUNK_SPANS = 100;

  private static final SpanAdmission UNLIMITED = new SpanAdmission(null);

  /**
   * @param spanLimit the span limit; zero or negative disables the budget
   */
  public static SpanAdmission of(long spanLimit) {
    return spanLimit <= 0 ? UNLIMITED : new SpanAdmission(new AdmissionBudget(spanLimit));
  }

  /** Admits everything and charges nothing. */
  public static SpanAdmission unlimited() {
    return UNLIMITED;
  }

  private final AdmissionBudget budget;
  private final long sampledOutCutoff;
  private final long forceKeepCutoff;

  private SpanAdmission(AdmissionBudget budget) {
    this.budget = budget;
    if (budget == null) {
      this.sampledOutCutoff = Long.MAX_VALUE;
      this.forceKeepCutoff = Long.MAX_VALUE;
    } else {
      long limit = budget.limit();
      this.sampledOutCutoff = Math.max(1, limit / 4);
      this.forceKeepCutoff = limit > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : limit * 2;
    }
  }

  public boolean isUnlimited() {
    return budget == null;
  }

  /** Spans admitted and not yet released; zero when unlimited. */
  public long used() {
    return budget == null ? 0 : budget.used();
  }

  public boolean tryAdmitSampledOut(int spans) {
    return budget == null || budget.tryAcquire(spans, sampledOutCutoff);
  }

  public boolean tryAdmitKept(int spans) {
    return budget == null || budget.tryAcquire(spans, keptCutoff(spans));
  }

  public boolean tryAdmitForceKept(int spans) {
    return budget == null || budget.tryAcquire(spans, forceKeepCutoff);
  }

  /** Returns the spans of an admitted trace once the writer no longer holds them. */
  public void release(int spans) {
    if (budget != null) {
      budget.release(spans);
    }
  }

  /** The span cap the cutoffs are taken from, or {@link Long#MAX_VALUE} when unlimited. */
  public long limit() {
    return budget == null ? Long.MAX_VALUE : budget.limit();
  }

  long keptCutoff(int spans) {
    long limit = budget.limit();
    long half = limit / 2;
    long scaled = Math.min(Math.max(spans, 0), LARGE_CHUNK_SPANS);
    return Math.max(1, half + (limit - half) * scaled / LARGE_CHUNK_SPANS);
  }
}
