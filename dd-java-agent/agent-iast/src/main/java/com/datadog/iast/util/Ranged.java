package com.datadog.iast.util;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

public interface Ranged {

  int getStart();

  int getLength();

  /**
   * Checks if the two ranges have values in common
   *
   * @param range range to check for common values
   * @return {@code true} if {@code this} has common values with the range
   */
  default boolean intersects(final Ranged range) {
    return range.getStart() < (getStart() + getLength())
        && (range.getStart() + range.getLength() > getStart());
  }

  /**
   * Checks if the range fully contains the range passed as parameter
   *
   * @param range range to check if it fully contains
   * @return {@code} if {@code this} is fully contained in the range
   */
  default boolean contains(final Ranged range) {
    if (getStart() > range.getStart()) {
      return false;
    }
    return (getStart() + getLength()) >= (range.getStart() + range.getLength());
  }

  /**
   * Removes the values from the range
   *
   * @param range range whose values are to be removed
   * @return list with the ranges present in {@code this} but not in the range
   */
  default List<Ranged> remove(final Ranged range) {
    if (!intersects(range)) {
      return singletonList(this);
    } else if (range.contains(this)) {
      return emptyList();
    } else {
      final List<Ranged> result = new ArrayList<>(3);
      if (range.getStart() > getStart()) {
        result.add(build(getStart(), range.getStart() - getStart()));
      }
      int end = getStart() + getLength();
      int rangeEnd = range.getStart() + range.getLength();
      if (rangeEnd < end) {
        result.add(build(rangeEnd, (end - rangeEnd)));
      }
      return result;
    }
  }

  /** Computes the intersection of the ranges or {@code null} if they do not intersect */
  @Nullable
  default Ranged intersection(final Ranged range) {
    if (this.getStart() == range.getStart() && this.getLength() == range.getLength()) {
      return this;
    }
    final Ranged lead, trail;
    if (getStart() < range.getStart()) {
      lead = this;
      trail = range;
    } else {
      lead = range;
      trail = this;
    }
    final int start = Math.max(lead.getStart(), trail.getStart());
    final int end =
        Math.min(lead.getStart() + lead.getLength(), trail.getStart() + trail.getLength());
    if (start >= end) {
      return null;
    } else {
      return build(start, end - start);
    }
  }

  default boolean isBefore(@Nullable final Ranged range) {
    if (range == null) {
      return true;
    }
    final int offset = getStart() - range.getStart();
    if (offset == 0) {
      return getLength() <= range.getLength(); // put smaller ranges first
    }
    return offset < 0;
  }

  static Ranged build(int start, int length) {
    return new RangedImpl(start, length);
  }

  class RangedImpl implements Ranged {
    private final int start;
    private final int length;

    public RangedImpl(final int start, final int length) {
      this.start = start;
      this.length = length;
    }

    @Override
    public int getStart() {
      return start;
    }

    @Override
    public int getLength() {
      return length;
    }
  }
}
