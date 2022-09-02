package com.datadog.iast.model;

import java.util.Arrays;
import java.util.Objects;
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

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Evidence evidence = (Evidence) o;
    return Objects.equals(value, evidence.value) && Arrays.equals(ranges, evidence.ranges);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(value);
    result = 31 * result + Arrays.hashCode(ranges);
    return result;
  }
}
