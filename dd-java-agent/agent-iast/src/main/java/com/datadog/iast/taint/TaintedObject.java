package com.datadog.iast.taint;

import static com.datadog.iast.taint.TaintedMap.POSITIVE_MASK;

import com.datadog.iast.model.Range;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class TaintedObject extends WeakReference<Object> {
  final int positiveHashCode;
  TaintedObject next;
  private Range[] ranges;

  public TaintedObject(
      final @Nonnull Object obj,
      final @Nonnull Range[] ranges,
      final @Nullable ReferenceQueue<Object> queue) {
    super(obj, queue);
    this.positiveHashCode = System.identityHashCode(obj) & POSITIVE_MASK;
    this.ranges = ranges;
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
