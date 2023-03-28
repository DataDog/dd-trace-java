package datadog.trace.instrumentation.trace_annotation;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class TraceDecorator extends BaseDecorator {
  public static TraceDecorator DECORATE = new TraceDecorator();
  public static final Boolean useLegacyOperationName =
      Config.get().isLegacyTracingEnabled(true, "trace.annotations");
  private static final CharSequence TRACE = UTF8BytesString.create("trace");

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

  public boolean useLegacyOperationName() {
    return useLegacyOperationName;
  }

  public AgentSpan measureSpan(final AgentSpan span) {
    span.setTag(DDTags.MEASURED, 1);
    return span;
  }
}
