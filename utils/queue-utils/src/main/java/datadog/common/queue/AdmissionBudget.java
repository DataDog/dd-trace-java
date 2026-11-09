package datadog.common.queue;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * A shared budget of cost units, such as spans, held by work that has been admitted but not yet
 * processed.
 *
 * <p>Admission checks headroom, not fit. {@link #tryAcquire(long, long)} admits when usage before
 * the charge is below the caller's cutoff, then charges the full cost. An expensive item is
 * admitted whenever a cheap one would be, so a nearly full budget does not turn away only the
 * expensive items. The price is overshoot: usage can exceed a cutoff by the cost of the items
 * admitted while it was below. Callers bound the overshoot by bounding item cost.
 *
 * <p>A refused acquire only reads the counter. A saturated budget therefore adds no contended
 * writes to the rejection path, which is the common path while it stays saturated.
 *
 * <p>Each admitted cost must be released exactly once, with the same value it was acquired with.
 * Release is not checked against acquisition; a mismatched release skews every later decision.
 */
public final class AdmissionBudget {
  private static final AtomicLongFieldUpdater<AdmissionBudget> USED =
      AtomicLongFieldUpdater.newUpdater(AdmissionBudget.class, "used");

  private final long limit;
  private volatile long used;

  /**
   * @param limit the nominal capacity callers take their cutoffs from; must be positive
   */
  public AdmissionBudget(long limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive: " + limit);
    }
    this.limit = limit;
  }

  public long limit() {
    return limit;
  }

  /** The cost admitted and not yet released. May exceed {@link #limit()}. */
  public long used() {
    return used;
  }

  /**
   * Admits and charges {@code cost} if usage is below {@code cutoff}.
   *
   * <p>The cutoff need not equal the limit. Callers that rank work can give each rank its own
   * cutoff, lower ones shed first as usage rises, and a cutoff above the limit lets favored work
   * past it.
   *
   * @return whether the cost was admitted and charged
   */
  public boolean tryAcquire(long cost, long cutoff) {
    if (used >= cutoff) {
      return false;
    }
    USED.getAndAdd(this, cost);
    return true;
  }

  /** Returns cost charged by an earlier successful acquire. */
  public void release(long cost) {
    USED.getAndAdd(this, -cost);
  }
}
