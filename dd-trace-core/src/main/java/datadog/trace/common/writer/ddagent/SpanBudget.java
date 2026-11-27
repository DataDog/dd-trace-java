package datadog.trace.common.writer.ddagent;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * Bounds the spans the writer holds between publish and serialization.
 *
 * <p>The writer's queues are bounded by trace count, which says nothing about memory: a stalled
 * agent leaves them full of traces of any size. This budget counts spans instead, shared across
 * every writer queue. A trace's spans are added when it is enqueued, and released once the
 * serializer has consumed the trace, or when the trace is dropped after admission.
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
 * <p>Admission checks headroom, not fit: a trace is admitted when usage before it is below its
 * cutoff, and then all its spans are counted. A large trace is admitted whenever a small one would
 * be, so a nearly full budget does not turn away only the large traces. Usage can overshoot a
 * cutoff by the traces admitted while it was below; partial flush bounds chunk size, which bounds
 * that.
 *
 * <p>A refused trace only reads the counter, so a saturated budget adds no contended writes to the
 * rejection path, which is the common path while it stays saturated. Each admitted trace must be
 * released exactly once, with the spans it was admitted with.
 */
public final class SpanBudget {
  /** Chunk size at which a kept trace is admitted up to the full limit. */
  static final int LARGE_CHUNK_SPANS = 100;

  private static final AtomicLongFieldUpdater<SpanBudget> USED =
      AtomicLongFieldUpdater.newUpdater(SpanBudget.class, "used");

  private static final SpanBudget UNLIMITED = new SpanBudget(0);

  /**
   * @param spanLimit the span limit; zero or negative disables the budget
   */
  public static SpanBudget of(long spanLimit) {
    return spanLimit <= 0 ? UNLIMITED : new SpanBudget(spanLimit);
  }

  /** Admits everything and counts nothing. */
  public static SpanBudget unlimited() {
    return UNLIMITED;
  }

  private final boolean unlimited;
  private final long limit;
  private final long halfLimit;
  private final long sampledOutCutoff;
  private final long forceKeepCutoff;

  /** Spans admitted and not yet released. */
  private volatile long used;

  private SpanBudget(long spanLimit) {
    this.unlimited = spanLimit <= 0;
    if (unlimited) {
      this.limit = Long.MAX_VALUE;
      this.halfLimit = Long.MAX_VALUE;
      this.sampledOutCutoff = Long.MAX_VALUE;
      this.forceKeepCutoff = Long.MAX_VALUE;
    } else {
      this.limit = spanLimit;
      this.halfLimit = spanLimit / 2;
      this.sampledOutCutoff = Math.max(1, spanLimit / 4);
      this.forceKeepCutoff = spanLimit > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : spanLimit * 2;
    }
  }

  public boolean isUnlimited() {
    return unlimited;
  }

  /** Spans admitted and not yet released; may exceed {@link #limit()}. Zero when unlimited. */
  public long used() {
    return used;
  }

  /** The span cap the cutoffs are taken from, or {@link Long#MAX_VALUE} when unlimited. */
  public long limit() {
    return limit;
  }

  public boolean tryAdmitSampledOut(int spans) {
    return tryAdmit(spans, sampledOutCutoff);
  }

  public boolean tryAdmitKept(int spans) {
    return tryAdmit(spans, keptCutoff(spans));
  }

  public boolean tryAdmitForceKept(int spans) {
    return tryAdmit(spans, forceKeepCutoff);
  }

  /** Returns the spans of an admitted trace once the writer no longer holds them. */
  public void release(int spans) {
    if (!unlimited) {
      USED.getAndAdd(this, -spans);
    }
  }

  private boolean tryAdmit(int spans, long cutoff) {
    if (unlimited) {
      return true;
    }
    if (used >= cutoff) {
      return false;
    }
    USED.getAndAdd(this, spans);
    return true;
  }

  long keptCutoff(int spans) {
    long scaled = Math.min(spans, LARGE_CHUNK_SPANS);
    return Math.max(1, halfLimit + (limit - halfLimit) * scaled / LARGE_CHUNK_SPANS);
  }
}
