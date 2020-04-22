package com.datadog.profiling.jfr;

public interface NamedType {
  String getTypeName();

  default boolean isSame(NamedType other) {
    return getTypeName().equals(other.getTypeName());
  }
}
