package com.datadog.iast;

import datadog.trace.api.Config;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.annotation.Nullable;

public interface IastTag {

  void setTagTop(@Nullable final TraceSegment trace);

  void setTag(@Nullable final AgentSpan span);

  abstract class BaseTag<E> implements IastTag {

    protected abstract String key();

    protected abstract E value();

    @Override
    public void setTagTop(@Nullable final TraceSegment trace) {
      if (trace != null) {
        trace.setTagTop(key(), value());
      }
    }

    @Override
    public void setTag(@Nullable final AgentSpan span) {
      if (span != null) {
        span.setTag(key(), value());
      }
    }
  }

  class NoOp implements IastTag {
    private static final IastTag INSTANCE = new NoOp();

    @Override
    public void setTagTop(@Nullable TraceSegment trace) {}

    @Override
    public void setTag(@Nullable AgentSpan span) {}
  }

  /**
   * Sets the value for {@code "_dd.iast.enabled"} in the requests, if IAST is not full activated it
   * should not set any values.
   *
   * <ul>
   *   <li>{@code 0} for requests skipped by sampling.
   *   <li>{@code 1} for requests analyzed by IAST.
   * </ul>
   */
  class Enabled extends BaseTag<Integer> {

    public static final IastTag SKIPPED = Enabled.withValue(0);

    public static final IastTag ANALYZED = Enabled.withValue(1);

    private final int value;

    private Enabled(int value) {
      this.value = value;
    }

    @Override
    protected String key() {
      return "_dd.iast.enabled";
    }

    @Override
    protected Integer value() {
      return value;
    }

    public static IastTag withValue(final int value) {
      if (Config.get().getIastActivation() == ProductActivation.FULLY_ENABLED) {
        return new Enabled(value);
      } else {
        return NoOp.INSTANCE;
      }
    }
  }
}
