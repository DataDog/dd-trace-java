package datadog.trace.core;

import java.util.ArrayList;

/** ArrayList that exposes modCount to allow for an optimization in TraceInterceptor handling */
public final class SpanList extends ArrayList<DDSpan> {
  static final SpanList EMPTY = new SpanList(0);

  /**
   * Hint set by {@code PendingTrace} when the list is assembled: how many spans pass the
   * CSS-eligibility predicate. The CSS aggregator uses this to size its ring claim exactly instead
   * of overclaiming. {@code -1} means "unknown" -- the consumer must fall back to counting itself
   * (or to overclaiming based on {@code size()}).
   */
  private int eligibleCount = -1;

  /**
   * Convenience function for creating a SpanList containing a single DDSpan. Meant as replacement
   * for Collections.singletonList when creating a SpanList.
   *
   * @param span != null
   * @return a SpanList
   */
  static final SpanList of(DDSpan span) {
    SpanList list = new SpanList(1);
    list.add(span);
    return list;
  }

  /**
   * Constructs a SpanList with the specified capacity
   *
   * @param capacity
   */
  SpanList(int capacity) {
    super(capacity);
  }

  /** The modifcation count of the List - can be used to check if the List has been altered */
  int modCount() {
    return this.modCount;
  }

  /** See {@link #eligibleCount}; called from {@code PendingTrace} during list assembly. */
  void setMetricEligibleCount(int count) {
    this.eligibleCount = count;
  }

  /** {@code -1} if unset (e.g. after TraceInterceptors built a fresh SpanList). */
  public int getMetricEligibleCount() {
    return eligibleCount;
  }
}
