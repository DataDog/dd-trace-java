package datadog.trace.instrumentation.trace_annotation;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class TraceDecorator extends BaseDecorator {
  public static TraceDecorator DECORATE = new TraceDecorator();

  private static final CharSequence TRACE = UTF8BytesString.createConstant("trace");

  @Override
  protected String[] instrumentationNames() {
    // Can't use "trace" because that's used as the general config name:
    return new String[] {"trace-annotation", "trace-config"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return TRACE;
  }
}
