package com.datadog.iast.model;

import com.datadog.iast.model.json.SourceTypeString;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.Taintable;
import java.lang.ref.Reference;
import java.util.Objects;
import java.util.StringJoiner;
import javax.annotation.Nullable;

public final class Source implements Taintable.Source {

  // value to send in the rare case that the name/value have been garbage collected
  private static final String GARBAGE_COLLECTED_REF = "[GCed]";

  private final @SourceTypeString byte origin;
  @Nullable private final Object name;
  @Nullable private final Object value;

  public Source(final byte origin, @Nullable final Object name, @Nullable final Object value) {
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
    return asString(name);
  }

  @Override
  @Nullable
  public String getValue() {
    return asString(value);
  }

  @Nullable
  private String asString(@Nullable final Object target) {
    Object value = target;
    if (value instanceof Reference) {
      value = ((Reference<?>) value).get();
      if (value == null) {
        value = GARBAGE_COLLECTED_REF;
      }
    }
    return value instanceof String ? (String) value : null;
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", Source.class.getSimpleName() + "[", "]")
        .add("origin=" + SourceTypes.toString(origin))
        .add("name='" + getName() + "'")
        .add("value='" + getValue() + "'")
        .toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Source source = (Source) o;
    return origin == source.origin
        && Objects.equals(getName(), source.getName())
        && Objects.equals(getValue(), source.getValue());
  }

  @Override
  public int hashCode() {
    return Objects.hash(origin, getName(), getValue());
  }
}
