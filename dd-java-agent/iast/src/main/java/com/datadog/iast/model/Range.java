package com.datadog.iast.model;

import com.datadog.iast.model.json.SourceIndex;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

public final class Range {
  private final @Nonnegative int start;
  private final @Nonnegative int length;
  private final @Nonnull @SourceIndex Source source;

  public Range(final int start, final int length, final Source source) {
    this.start = start;
    this.length = length;
    this.source = source;
  }

  public int getStart() {
    return start;
  }

  public int getLength() {
    return length;
  }

  public Source getSource() {
    return source;
  }
}
