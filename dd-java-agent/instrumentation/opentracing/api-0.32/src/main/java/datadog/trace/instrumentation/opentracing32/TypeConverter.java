package datadog.trace.instrumentation.opentracing32;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
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
  private final OTScopeManager.OTScope noopScopeWrapperFinishSpanOnClose;

  public TypeConverter(final LogHandler logHandler) {
    this.logHandler = logHandler;
    noopSpanWrapper = new OTSpan(AgentTracer.NoopAgentSpan.INSTANCE, this, logHandler);
    noopContextWrapper = new OTSpanContext(AgentTracer.NoopContext.INSTANCE);
    noopScopeWrapper = new OTScopeManager.OTScope(AgentTracer.NoopAgentScope.INSTANCE, false, this);
    noopScopeWrapperFinishSpanOnClose =
        new OTScopeManager.OTScope(AgentTracer.NoopAgentScope.INSTANCE, true, this);
  }

  public AgentSpan toAgentSpan(final Span span) {
    if (span instanceof OTSpan) {
      return ((OTSpan) span).getDelegate();
    }
    return null == span ? null : AgentTracer.NoopAgentSpan.INSTANCE;
  }

  public OTSpan toSpan(final AgentSpan agentSpan) {
    if (agentSpan == null) {
      return null;
    }
    // check if a wrapper has already been created and attached to the agent span
    Object wrapper = agentSpan.getWrapper();
    if (wrapper instanceof OTSpan) {
      return (OTSpan) wrapper;
    }
    // avoid a new OTSpan wrapper allocation for the noop span
    if (agentSpan == AgentTracer.NoopAgentSpan.INSTANCE) {
      return noopSpanWrapper;
    }
    OTSpan otSpan = new OTSpan(agentSpan, this, logHandler);
    agentSpan.attachWrapper(otSpan);
    return otSpan;
  }

  public Scope toScope(final AgentScope scope, final boolean finishSpanOnClose) {
    if (scope == null) {
      return null;
    }
    // check if a wrapper has already been created and attached to the agent scope
    Object wrapper = scope.getWrapper(finishSpanOnClose);
    if (wrapper instanceof OTScopeManager.OTScope) {
      return (OTScopeManager.OTScope) wrapper;
    }
    // avoid a new OTScope wrapper allocation for the noop scope
    if (scope == AgentTracer.NoopAgentScope.INSTANCE) {
      return finishSpanOnClose ? noopScopeWrapperFinishSpanOnClose : noopScopeWrapper;
    }
    OTScopeManager.OTScope otScope = new OTScopeManager.OTScope(scope, finishSpanOnClose, this);
    scope.attachWrapper(otScope, finishSpanOnClose);
    return otScope;
  }

  public SpanContext toSpanContext(final AgentSpan.Context context) {
    if (context == null) {
      return null;
    }
    // check if a wrapper has already been created and attached to the agent span context
    Object wrapper = context.getWrapper();
    if (wrapper instanceof OTSpanContext) {
      return (OTSpanContext) wrapper;
    }
    // avoid a new SpanContext wrapper allocation for the noop context
    if (context == AgentTracer.NoopContext.INSTANCE) {
      return noopContextWrapper;
    }
    OTSpanContext otSpanContext = new OTSpanContext(context);
    context.attachWrapper(otSpanContext);
    return otSpanContext;
  }

  public AgentSpan.Context toContext(final SpanContext spanContext) {
    if (spanContext instanceof OTSpanContext) {
      return ((OTSpanContext) spanContext).getDelegate();
    }
    return null == spanContext ? null : AgentTracer.NoopContext.INSTANCE;
  }
}
