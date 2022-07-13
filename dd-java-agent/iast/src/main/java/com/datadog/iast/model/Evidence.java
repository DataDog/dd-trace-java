package com.datadog.iast.model;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class Evidence {

  private final @Nonnull String value;

  private final @Nullable Range[] ranges;

  public Evidence(final String value) {
    this(value, null);
  }

  public Evidence(final String value, final Range[] ranges) {
    this.value = value;
    this.ranges = ranges;
  }

  public String getValue() {
    return value;
  }

  public Range[] getRanges() {
    return ranges;
  }
}
