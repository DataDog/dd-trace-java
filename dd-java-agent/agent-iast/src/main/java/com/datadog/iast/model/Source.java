package com.datadog.iast.model;

import com.datadog.iast.model.json.SourceTypeString;
import java.util.Objects;

public final class Source {
  private final @SourceTypeString byte origin;
  private final String name;
  private final String value;

  public Source(final byte origin, final String name, final String value) {
    this.origin = origin;
    this.name = name;
    this.value = value;
  }

  public byte getOrigin() {
    return origin;
  }

  public String getName() {
    return name;
  }

  public String getValue() {
    return value;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Source source = (Source) o;
    return origin == source.origin
        && Objects.equals(name, source.name)
        && Objects.equals(value, source.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(origin, name, value);
  }
}
