package com.datadog.iast.taint;

import static com.datadog.iast.taint.TaintedMap.POSITIVE_MASK;

import com.datadog.iast.model.Range;
import datadog.trace.api.Config;
import java.lang.ref.WeakReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class TaintedObject extends WeakReference<Object> {

  public static final int MAX_RANGE_COUNT = Config.get().getIastMaxRangeCount();

  final int positiveHashCode;
  @Nullable TaintedObject next;
  private Range[] ranges;

  /** generation of the tainted for max age purging purposes */
  boolean generation;

  public TaintedObject(final @Nonnull Object obj, final @Nonnull Range[] ranges) {
    super(obj);
    this.positiveHashCode = System.identityHashCode(obj) & POSITIVE_MASK;
    // ensure ranges never go over the limit
    if (ranges.length > MAX_RANGE_COUNT) {
      this.ranges = new Range[MAX_RANGE_COUNT];
      System.arraycopy(ranges, 0, this.ranges, 0, MAX_RANGE_COUNT);
    } else {
      this.ranges = ranges;
    }
  }

  /**
   * Get ranges. The array or its elements MUST NOT be mutated. This may be reused in multiple
   * instances.
   */
  @Nonnull
  public Range[] getRanges() {
    return ranges;
  }

  public void setRanges(@Nonnull final Range[] ranges) {
    this.ranges = ranges;
  }
}
