package com.datadog.profiling.ddprof;

public enum ContextEnum {
  FOO,
  BAR;

  @Override
  public String toString() {
    return name().toLowerCase();
  }
}
