package com.datadog.iast.model;

import com.datadog.iast.model.json.SourceTypeString;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.Taintable;
import java.util.Objects;
import java.util.StringJoiner;
import javax.annotation.Nullable;

public final class Source implements Taintable.Source {
  private final @SourceTypeString byte origin;
  @Nullable private final String name;
  @Nullable private final String value;

  public Source(
      final byte origin, @Nullable final CharSequence name, @Nullable final CharSequence value) {
    this(origin, name == null ? null : name.toString(), value == null ? null : value.toString());
  }

  public Source(final byte origin, @Nullable final String name, @Nullable final String value) {
    this.origin = origin;
    this.name = name;
    this.value = value;
  }

  @Override
  public byte getOrigin() {
    return origin;
  }

  @Override
  @Nullable
  public String getName() {
    return name;
  }

  @Override
  @Nullable
  public String getValue() {
    return value;
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", Source.class.getSimpleName() + "[", "]")
        .add("origin=" + SourceTypes.toString(origin))
        .add("name='" + name + "'")
        .add("value='" + value + "'")
        .toString();
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
