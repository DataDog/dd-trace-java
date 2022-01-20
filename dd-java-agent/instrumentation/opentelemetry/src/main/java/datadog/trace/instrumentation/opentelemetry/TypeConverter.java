package datadog.trace.instrumentation.opentelemetry;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.trace.Span;
import io.opentelemetry.trace.SpanContext;

// Centralized place to do conversions
public class TypeConverter {
  private final Span noopSpanWrapper;
  private final SpanContext noopContextWrapper;

  public TypeConverter() {
    noopSpanWrapper = new OtelSpan(AgentTracer.NoopAgentSpan.INSTANCE, this);
    noopContextWrapper = new OtelSpanContext(AgentTracer.NoopContext.INSTANCE);
  }

  public AgentSpan toAgentSpan(final Span span) {
    if (span instanceof OtelSpan) {
      return ((OtelSpan) span).getDelegate();
    }
    return null == span ? null : AgentTracer.NoopAgentSpan.INSTANCE;
  }

  public Span toSpan(final AgentSpan agentSpan) {
    if (agentSpan == null) {
      return null;
    }
    // check if a wrapper has already been created and attached to the agent span
    Object wrapper = agentSpan.getWrapper();
    if (wrapper instanceof OtelSpan) {
      return (OtelSpan) wrapper;
    }
    // avoid a new Span wrapper allocation for a noop span
    if (agentSpan == AgentTracer.NoopAgentSpan.INSTANCE) {
      return noopSpanWrapper;
    }
    OtelSpan otSpan = new OtelSpan(agentSpan, this);
    agentSpan.attachWrapper(otSpan);
    return otSpan;
  }

  public Scope toScope(final AgentScope scope) {
    if (scope == null) {
      return null;
    }
    return new OtelScope(scope);
  }

  public SpanContext toSpanContext(final AgentSpan.Context context) {
    if (context == null) {
      return null;
    }
    // check if a wrapper has already been created and attached to the agent span context
    Object wrapper = context.getWrapper();
    if (wrapper instanceof OtelSpanContext) {
      return (OtelSpanContext) wrapper;
    }
    // avoid a new SpanContext wrapper allocation for the noop context
    if (context == AgentTracer.NoopContext.INSTANCE) {
      return noopContextWrapper;
    }
    OtelSpanContext otelSpanContext = new OtelSpanContext(context);
    context.attachWrapper(otelSpanContext);
    return otelSpanContext;
  }

  public AgentSpan.Context toContext(final SpanContext spanContext) {
    if (spanContext instanceof OtelSpanContext) {
      return ((OtelSpanContext) spanContext).getDelegate();
    }
    return null == spanContext ? null : AgentTracer.NoopContext.INSTANCE;
  }
}
