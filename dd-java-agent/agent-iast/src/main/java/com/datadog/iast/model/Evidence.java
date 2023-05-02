package com.datadog.iast.model;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class Evidence {

  private final @Nonnull String value;

  private final @Nullable Range[] ranges;

  /** Extra context needed for the evidence, for instance Database in case of a SQLi */
  private final transient @Nonnull Context context = new Evidence.Context(4);

  /** For deserialization in tests via moshi */
  private Evidence() {
    this(null, null);
  }

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

  @Nonnull
  public Context getContext() {
    return context;
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

  public static class Context {

    private final Map<String, Object> context;
    private final int maxSize;

    public Context(final int maxSize) {
      this.context = new HashMap<>();
      this.maxSize = maxSize;
    }

    public boolean put(final String key, final Object value) {
      if (context.size() >= maxSize && !context.containsKey(key)) {
        return false;
      }
      context.put(key, value);
      return true;
    }

    @SuppressWarnings("unchecked")
    public <E> E get(@Nonnull final String key) {
      return (E) context.get(key);
    }
  }
}
