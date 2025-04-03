package datadog.trace.instrumentation.opentracing32;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpanContext;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AttachableWrapper;
import datadog.trace.instrumentation.opentracing.LogHandler;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;

// Centralized place to do conversions
public class TypeConverter {
  private final LogHandler logHandler;
  private final OTSpan noopSpanWrapper;
  private final OTSpanContext noopContextWrapper;
  private final OTScopeManager.OTScope noopScopeWrapper;

  public TypeConverter(final LogHandler logHandler) {
    this.logHandler = logHandler;
    noopSpanWrapper = new OTSpan(noopSpan(), this, logHandler);
    noopContextWrapper = new OTSpanContext(noopSpanContext());
    noopScopeWrapper = new OTScopeManager.OTScope(noopScope(), false, this);
  }

  public AgentSpan toAgentSpan(final Span span) {
    if (span instanceof OTSpan) {
      return ((OTSpan) span).asAgentSpan();
    }
    return null == span ? null : noopSpan();
  }

  public OTSpan toSpan(final AgentSpan agentSpan) {
    if (agentSpan == null) {
      return null;
    }
    if (agentSpan instanceof AttachableWrapper) {
      AttachableWrapper attachableSpanWrapper = (AttachableWrapper) agentSpan;
      Object wrapper = attachableSpanWrapper.getWrapper();
      if (wrapper instanceof OTSpan) {
        return (OTSpan) wrapper;
      }
      OTSpan spanWrapper = new OTSpan(agentSpan, this, logHandler);
      attachableSpanWrapper.attachWrapper(spanWrapper);
      return spanWrapper;
    }
    if (agentSpan == noopSpan()) {
      return noopSpanWrapper;
    }
    return new OTSpan(agentSpan, this, logHandler);
  }

  public Scope toScope(final AgentScope scope, final boolean finishSpanOnClose) {
    if (scope == null) {
      return null;
    }
    if (scope == noopScope()) {
      return noopScopeWrapper;
    }
    return new OTScopeManager.OTScope(scope, finishSpanOnClose, this);
  }

  public SpanContext toSpanContext(final AgentSpanContext context) {
    if (context == null) {
      return null;
    }
    // avoid a new SpanContext wrapper allocation for the noop context
    if (context == noopSpanContext()) {
      return noopContextWrapper;
    }
    return new OTSpanContext(context);
  }

  public AgentSpanContext toContext(final SpanContext spanContext) {
    if (spanContext instanceof OTSpanContext) {
      return ((OTSpanContext) spanContext).getDelegate();
    }
    return null == spanContext ? null : noopSpanContext();
  }
}
