package datadog.trace.instrumentation.opentelemetry;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.trace.Span;
import io.opentelemetry.trace.SpanContext;

// Centralized place to do conversions
public class TypeConverter {
  // TODO maybe add caching to reduce new objects being created

  public AgentSpan toAgentSpan(final Span span) {
    if (span == null) {
      return null;
    } else if (span instanceof OtelSpan) {
      return ((OtelSpan) span).getDelegate();
    } else {
      // NOOP Span, otherwise arbitrary spans aren't supported.
      return AgentTracer.NoopAgentSpan.INSTANCE;
    }
  }

  public Span toSpan(final AgentSpan agentSpan) {
    if (agentSpan == null) {
      return null;
    }
    return new OtelSpan(agentSpan, this);
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
    return new OtelSpanContext(context);
  }

  public AgentSpan.Context toContext(final SpanContext spanContext) {
    if (spanContext == null) {
      return null;
    } else if (spanContext instanceof OtelSpanContext) {
      return ((OtelSpanContext) spanContext).getDelegate();
    } else {
      return AgentTracer.NoopContext.INSTANCE;
    }
  }
}
