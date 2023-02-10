package com.datadog.iast;

import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.annotation.Nullable;

public interface IastTag {

  Enabled SKIPPED = new Enabled(0);
  Enabled ANALYZED = new Enabled(1);

  String key();

  Object value();

  default void setTagTop(@Nullable final TraceSegment trace) {
    if (trace != null) {
      trace.setTagTop(key(), value());
    }
  }

  default void setTag(@Nullable final AgentSpan span) {
    if (span != null) {
      span.setTag(key(), value());
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
