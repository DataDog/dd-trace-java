package com.datadog.iast.model;

import com.datadog.iast.model.json.SourceIndex;
import com.datadog.iast.util.Ranged;
import java.util.Objects;
import java.util.StringJoiner;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

public final class Range implements Ranged {
  private final @Nonnegative int start;
  private final @Nonnegative int length;
  private final @Nonnull @SourceIndex Source source;

  public Range(final int start, final int length, final Source source) {
    this.start = start;
    this.length = length;
    this.source = source;
  }

  @Override
  public int getStart() {
    return start;
  }

  @Override
  public int getLength() {
    return length;
  }

  public Source getSource() {
    return source;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Range range = (Range) o;
    return start == range.start && length == range.length && Objects.equals(source, range.source);
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
    return new Range(start + offset, length, source);
  }
}
