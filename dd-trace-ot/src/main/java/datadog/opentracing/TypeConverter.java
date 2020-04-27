package datadog.opentracing;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.TagContext;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.noop.NoopSpan;

// Centralized place to do conversions
class TypeConverter {
  // TODO maybe add caching to reduce new objects being created

  private final LogHandler logHandler;

  public TypeConverter(final LogHandler logHandler) {
    this.logHandler = logHandler;
  }

  public AgentSpan toAgentSpan(final Span span) {
    if (span instanceof OTSpan) {
      return ((OTSpan) span).getDelegate();
    } else {
      // NOOP Span
      return AgentTracer.NoopAgentSpan.INSTANCE;
    }
  }

  public Span toSpan(final AgentSpan agentSpan) {
    if (agentSpan instanceof DDSpan) {
      return new OTSpan((DDSpan) agentSpan, this, logHandler);
    } else {
      // NOOP AgentSpans
      return NoopSpan.INSTANCE;
    }
  }

  // FIXME [API] Need to use the runtime type not compile-time type so "Object" is used
  // That fact that some methods return AgentScope and other TraceScope even though its the same
  // underlying object needs to be cleaned up
  public Scope toScope(final Object scope) {
    if (scope instanceof CustomScopeManagerWrapper.CustomScopeManagerScope) {
      return ((CustomScopeManagerWrapper.CustomScopeManagerScope) scope).getDelegate();
    } else if (scope instanceof TraceScope) {
      return new OTScopeManager.OTTraceScope((TraceScope) scope, this);
    } else {
      return new OTScopeManager.OTScope((AgentScope) scope, this);
    }
  }

  public SpanContext toSpanContext(final DDSpanContext context) {
    return new OTGenericContext(context);
  }

  public SpanContext toSpanContext(final TagContext tagContext) {
    if (tagContext instanceof ExtractedContext) {
      return new OTExtractedContext((ExtractedContext) tagContext);
    } else {
      return new OTTagContext(tagContext);
    }
  }

  public AgentSpan.Context toContext(final SpanContext spanContext) {
    // FIXME: [API] DDSpanContext, ExtractedContext, TagContext, AgentSpan.Context
    // don't share a meaningful hierarchy
    if (spanContext instanceof OTGenericContext) {
      return ((OTGenericContext) spanContext).getDelegate();
    } else if (spanContext instanceof OTExtractedContext) {
      return ((OTExtractedContext) spanContext).getDelegate();
    } else if (spanContext instanceof OTTagContext) {
      return ((OTTagContext) spanContext).getDelegate();
    } else {
      return AgentTracer.NoopContext.INSTANCE;
    }
  }
}
