package com.datadog.iast.model;

import com.datadog.iast.model.json.SourceTypeString;

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
}
