package datadog.trace.instrumentation.opentelemetry;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpanContext;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AttachableWrapper;
import io.opentelemetry.context.Scope;
import io.opentelemetry.trace.Span;
import io.opentelemetry.trace.SpanContext;

// Centralized place to do conversions
public class TypeConverter {
  private final Span noopSpanWrapper;
  private final SpanContext noopContextWrapper;
  private final OtelScope noopScopeWrapper;

  public TypeConverter() {
    noopSpanWrapper = new OtelSpan(noopSpan(), this);
    noopContextWrapper = new OtelSpanContext(noopSpanContext());
    noopScopeWrapper = new OtelScope(noopScope());
  }

  public AgentSpan toAgentSpan(final Span span) {
    if (span instanceof OtelSpan) {
      return ((OtelSpan) span).asAgentSpan();
    }
    return null == span ? null : noopSpan();
  }

  public Span toSpan(final AgentSpan agentSpan) {
    if (agentSpan == null) {
      return null;
    }
    if (agentSpan instanceof AttachableWrapper) {
      AttachableWrapper attachableSpanWrapper = (AttachableWrapper) agentSpan;
      Object wrapper = attachableSpanWrapper.getWrapper();
      if (wrapper instanceof Span) {
        return (Span) wrapper;
      }
      OtelSpan spanWrapper = new OtelSpan(agentSpan, this);
      attachableSpanWrapper.attachWrapper(spanWrapper);
      return spanWrapper;
    }
    if (agentSpan == noopSpan()) {
      return noopSpanWrapper;
    }
    return new OtelSpan(agentSpan, this);
  }

  public Scope toScope(final AgentScope scope) {
    if (scope == null) {
      return null;
    }
    if (scope == noopScope()) {
      return noopScopeWrapper;
    }
    return new OtelScope(scope);
  }

  public SpanContext toSpanContext(final AgentSpanContext context) {
    if (context == null) {
      return null;
    }
    // avoid a new SpanContext wrapper allocation for the noop context
    if (context == noopSpanContext()) {
      return noopContextWrapper;
    }
    return new OtelSpanContext(context);
  }

  public AgentSpanContext toContext(final SpanContext spanContext) {
    if (spanContext instanceof OtelSpanContext) {
      return ((OtelSpanContext) spanContext).getDelegate();
    }
    return null == spanContext ? null : noopSpanContext();
  }
}
