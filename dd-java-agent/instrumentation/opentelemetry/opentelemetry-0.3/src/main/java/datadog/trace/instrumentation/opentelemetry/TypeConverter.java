package datadog.trace.instrumentation.opentelemetry;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
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
    noopSpanWrapper = new OtelSpan(AgentTracer.NoopAgentSpan.INSTANCE, this);
    noopContextWrapper = new OtelSpanContext(AgentTracer.NoopContext.INSTANCE);
    noopScopeWrapper = new OtelScope(AgentTracer.NoopAgentScope.INSTANCE);
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
    if (agentSpan instanceof AttachableWrapper) {
      AttachableWrapper attachableSpanWrapper = (AttachableWrapper) agentSpan;
      Object wrapper = attachableSpanWrapper.getWrapper();
      if (wrapper instanceof Span) {
        return (Span) wrapper;
      }
      Span spanWrapper = new OtelSpan(agentSpan, this);
      attachableSpanWrapper.attachWrapper(spanWrapper);
      return spanWrapper;
    }
    if (agentSpan == AgentTracer.NoopAgentSpan.INSTANCE) {
      return noopSpanWrapper;
    }
    return new OtelSpan(agentSpan, this);
  }

  public Scope toScope(final AgentScope scope) {
    if (scope == null) {
      return null;
    }
    if (scope instanceof AttachableWrapper) {
      AttachableWrapper attachableScopeWrapper = (AttachableWrapper) scope;
      Object wrapper = attachableScopeWrapper.getWrapper();
      if (wrapper instanceof Scope) {
        return (Scope) wrapper;
      }
      Scope otScope = new OtelScope(scope);
      attachableScopeWrapper.attachWrapper(otScope);
      return otScope;
    }
    if (scope == AgentTracer.NoopAgentScope.INSTANCE) {
      return noopScopeWrapper;
    }
    return new OtelScope(scope);
  }

  public SpanContext toSpanContext(final AgentSpan.Context context) {
    if (context == null) {
      return null;
    }
    // avoid a new SpanContext wrapper allocation for the noop context
    if (context == AgentTracer.NoopContext.INSTANCE) {
      return noopContextWrapper;
    }
    return new OtelSpanContext(context);
  }

  public AgentSpan.Context toContext(final SpanContext spanContext) {
    if (spanContext instanceof OtelSpanContext) {
      return ((OtelSpanContext) spanContext).getDelegate();
    }
    return null == spanContext ? null : AgentTracer.NoopContext.INSTANCE;
  }
}
