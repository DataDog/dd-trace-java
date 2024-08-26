package com.datadog.iast.taint;

import static com.datadog.iast.taint.TaintedMap.POSITIVE_MASK;

import com.datadog.iast.model.Range;
import datadog.trace.api.Config;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaintedObject extends WeakReference<Object> {

  private static final Logger LOGGER = LoggerFactory.getLogger(TaintedObject.class);

  public static final int MAX_RANGE_COUNT = Config.get().getIastMaxRangeCount();

  final int positiveHashCode;
  @Nullable TaintedObject next;
  private Range[] ranges;

  /** generation of the tainted for max age purging purposes */
  boolean generation;

  public TaintedObject(final @Nonnull Object obj, final @Nonnull Range[] ranges) {
    super(obj);
    validateRanges(ranges);
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
    try {
      validateRanges(ranges);
      this.ranges = ranges;
    } catch (Throwable e) {
      LOGGER.debug("Error tainting object with custom ranges, ranges won't be updated", e);
    }
  }

  @Override
  public String toString() {
    final Object referent = get();
    return "[hash: "
        + positiveHashCode
        + ", gen: "
        + generation
        + "] "
        + (referent == null ? "GCed" : referent)
        + " ("
        + (ranges == null ? 0 : ranges.length)
        + " ranges)";
  }

  private void validateRanges(final Range[] ranges) {
    if (ranges == null) {
      throw new IllegalArgumentException("ranges cannot be null");
    }
    for (Range range : ranges) {
      if (range == null) {
        throw new IllegalArgumentException("found null range in " + Arrays.toString(ranges));
      }
    }
  }
}
