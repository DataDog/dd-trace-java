package com.datadog.iast.model;

public final class Source {
  private final SourceType origin;

  private final String name;

  private final String value;

  public Source(final SourceType origin, final String name, final String value) {
    this.origin = origin;
    this.name = name;
    this.value = value;
  }

  public SourceType getOrigin() {
    return origin;
  }

  public String getName() {
    return name;
  }

  public String getValue() {
    return value;
  }
}
