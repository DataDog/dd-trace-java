package com.datadog.iast.model;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import datadog.trace.api.iast.taint.Range;
import datadog.trace.api.iast.taint.Source;
import datadog.trace.api.iast.util.Ranged;
import java.util.Objects;
import java.util.StringJoiner;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

public final class RangeImpl implements Ranged, Range {

  private final @Nonnegative int start;
  private final @Nonnegative int length;
  private final @Nonnull Source source;
  private final int marks;

  public RangeImpl(
      final int start, final int length, @Nonnull final Source source, final int marks) {
    this.start = start;
    this.length = length;
    this.source = source;
    this.marks = marks;
  }

  @Override
  public int getStart() {
    return start;
  }

  @Override
  public int getLength() {
    return length;
  }

  @Nonnull
  public Source getSource() {
    return source;
  }

  public int getMarks() {
    return marks;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Range range = (Range) o;
    return start == range.getStart()
        && length == range.getLength()
        && Objects.equals(source, range.getSource());
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", Range.class.getSimpleName() + "[", "]")
        .add("start=" + start)
        .add("length=" + length)
        .add("source=" + source)
        .toString();
  }

  @Override
  public int hashCode() {
    return Objects.hash(start, length, source);
  }

  public boolean isValid() {
    return start >= 0 && length >= 0 && source != null;
  }

  public Range shift(final int offset) {
    if (offset == 0) {
      return this;
    }
    return new RangeImpl(start + offset, length, source, marks);
  }

  @Override
  public boolean isMarked(final int mark) {
    return (marks & mark) != NOT_MARKED;
  }

  /** Creates a version of the range without weak references to be used in vulnerabilities */
  public Range consolidate() {
    if (!source.isReference()) {
      return this;
    }
    return new RangeImpl(
        start,
        length,
        new SourceImpl(source.getOrigin(), source.getName(), source.getValue()),
        marks);
  }
}
