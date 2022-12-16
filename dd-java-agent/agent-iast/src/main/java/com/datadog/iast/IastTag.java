package com.datadog.iast;

import datadog.trace.api.TraceSegment;
import javax.annotation.Nullable;

public interface IastTag {

  Enabled REQUEST_SKIPPED = new Enabled(0);
  Enabled REQUEST_ANALYZED = new Enabled(1);

  String key();

  Object value();

  default void setTagTop(@Nullable final TraceSegment trace) {
    if (trace != null) {
      trace.setTagTop(key(), value());
    }
  }

  class Enabled implements IastTag {

    private final int value;

    public Enabled(int value) {
      this.value = value;
    }

    @Override
    public String key() {
      return "_dd.iast.enabled";
    }

    @Override
    public Object value() {
      return value;
    }
  }
}
