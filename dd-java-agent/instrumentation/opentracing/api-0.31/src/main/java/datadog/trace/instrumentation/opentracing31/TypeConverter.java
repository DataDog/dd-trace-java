package datadog.trace.instrumentation.opentracing31;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AttachableScopeWrapper;
import datadog.trace.bootstrap.instrumentation.api.AttachableSpanWrapper;
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
    if (agentSpan instanceof AttachableSpanWrapper) {
      AttachableSpanWrapper attachableSpanWrapper = (AttachableSpanWrapper) agentSpan;
      Object wrapper = attachableSpanWrapper.getWrapper();
      if (wrapper instanceof OTSpan) {
        return (OTSpan) wrapper;
      }
      OTSpan spanWrapper = new OTSpan(agentSpan, this, logHandler);
      attachableSpanWrapper.attachWrapper(spanWrapper);
      return spanWrapper;
    }
    if (agentSpan == AgentTracer.NoopAgentSpan.INSTANCE) {
      return noopSpanWrapper;
    }
    return new OTSpan(agentSpan, this, logHandler);
  }

  public Scope toScope(final AgentScope scope, final boolean finishSpanOnClose) {
    if (scope == null) {
      return null;
    }
    if (scope instanceof AttachableScopeWrapper) {
      AttachableScopeWrapper attachableScopeWrapper = (AttachableScopeWrapper) scope;
      Object wrapper = attachableScopeWrapper.getWrapper(finishSpanOnClose);
      if (wrapper instanceof Scope) {
        return (Scope) wrapper;
      }
      Scope otScope = new OTScopeManager.OTScope(scope, finishSpanOnClose, this);
      attachableScopeWrapper.attachWrapper(otScope, finishSpanOnClose);
      return otScope;
    }
    if (scope == AgentTracer.NoopAgentScope.INSTANCE) {
      return finishSpanOnClose ? noopScopeWrapperFinishSpanOnClose : noopScopeWrapper;
    }
    return new OTScopeManager.OTScope(scope, finishSpanOnClose, this);
  }

  public SpanContext toSpanContext(final AgentSpan.Context context) {
    if (context == null) {
      return null;
    }
    // avoid a new SpanContext wrapper allocation for the noop context
    if (context == AgentTracer.NoopContext.INSTANCE) {
      return noopContextWrapper;
    }
    return new OTSpanContext(context);
  }

  public AgentSpan.Context toContext(final SpanContext spanContext) {
    if (spanContext instanceof OTSpanContext) {
      return ((OTSpanContext) spanContext).getDelegate();
    }
    return null == spanContext ? null : AgentTracer.NoopContext.INSTANCE;
  }
}
