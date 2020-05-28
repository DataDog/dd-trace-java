package datadog.trace.instrumentation.opentracing32;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import datadog.trace.instrumentation.opentracing.LogHandler;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;

// Centralized place to do conversions
public class TypeConverter {
  // TODO maybe add caching to reduce new objects being created

  private final LogHandler logHandler;

  public TypeConverter(final LogHandler logHandler) {
    this.logHandler = logHandler;
  }

  public AgentSpan toAgentSpan(final Span span) {
    if (span == null) {
      return null;
    } else if (span instanceof OTSpan) {
      return ((OTSpan) span).getDelegate();
    } else {
      // NOOP Span, otherwise arbitrary spans aren't supported.
      return AgentTracer.NoopAgentSpan.INSTANCE;
    }
  }

  public Span toSpan(final AgentSpan agentSpan) {
    if (agentSpan == null) {
      return null;
    }
    return new OTSpan(agentSpan, this, logHandler);
  }

  // FIXME [API] Need to use the runtime type not compile-time type so "Object" is used
  // That fact that some methods return AgentScope and other TraceScope even though its the same
  // underlying object needs to be cleaned up
  public Scope toScope(final Object scope, final boolean finishSpanOnClose) {
    if (scope == null) {
      return null;
    } else if (scope instanceof TraceScope) {
      return new OTScopeManager.OTTraceScope((TraceScope) scope, finishSpanOnClose, this);
    } else {
      return new OTScopeManager.OTScope((AgentScope) scope, finishSpanOnClose, this);
    }
  }

  public SpanContext toSpanContext(final AgentSpan.Context context) {
    if (context == null) {
      return null;
    }
    return new OTSpanContext(context);
  }

  public AgentSpan.Context toContext(final SpanContext spanContext) {
    if (spanContext == null) {
      return null;
    } else if (spanContext instanceof OTSpanContext) {
      return ((OTSpanContext) spanContext).getDelegate();
    } else {
      return AgentTracer.NoopContext.INSTANCE;
    }
  }
}
