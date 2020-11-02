package datadog.trace.instrumentation.opentelemetry;

import datadog.trace.api.DDId;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTrace;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import java.util.Map;

// Centralized place to do conversions
public final class TypeConverter {
  private final ContextStore<SpanContext, AgentSpan.Context> spanContextStore;

  public TypeConverter(ContextStore<SpanContext, AgentSpan.Context> spanContextStore) {
    this.spanContextStore = spanContextStore;
  }

  // TODO maybe add caching to reduce new objects being created

  // static to allow direct access from context advice.
  public static AgentSpan toAgentSpan(final Span span) {
    if (span == null) {
      return null;
    }
    if (span instanceof OtelSpan) {
      return ((OtelSpan) span).getDelegate();
    }
    if (span.getSpanContext().isValid()) {
      // TODO: PropagatedSpan which we don't have a good representation for.
      throw new UnsupportedOperationException();
    }

    return AgentTracer.NoopAgentSpan.INSTANCE;
  }

  Span toSpan(final AgentSpan agentSpan) {
    if (agentSpan == null) {
      return null;
    }
    return new OtelSpan(agentSpan, this);
  }

  SpanContext toSpanContext(final AgentSpan.Context context) {
    if (context == null) {
      return null;
    }
    SpanContext spanContext;
    if (context.isRemote()) {
      spanContext =
          SpanContext.createFromRemoteParent(
              context.getTraceId().toHexStringPadded(32),
              context.getSpanId().toHexStringPadded(16),
              TraceFlags.getSampled(),
              TraceState.getDefault());
    } else {
      spanContext =
          SpanContext.create(
              context.getTraceId().toHexStringPadded(32),
              context.getSpanId().toHexStringPadded(16),
              TraceFlags.getSampled(),
              TraceState.getDefault());
    }
    spanContextStore.put(spanContext, context);
    return spanContext;
  }

  AgentSpan.Context toAgentSpanContext(final SpanContext spanContext) {
    AgentSpan.Context context = spanContextStore.get(spanContext);
    if (context == null) {
      if (spanContext.isValid()) {
        context = new OtelSpanContext(spanContext);
      } else {
        context = AgentTracer.NoopContext.INSTANCE;
      }
      spanContextStore.put(spanContext, context);
    }
    return context;
  }

  private static final class OtelSpanContext implements AgentSpan.Context {
    private final DDId traceId;
    private final DDId spanId;
    private final boolean isRemote;

    public OtelSpanContext(SpanContext spanContext) {
      traceId = DDId.fromHex(spanContext.getTraceIdAsHexString().substring(16));
      spanId = DDId.fromHex(spanContext.getSpanIdAsHexString());
      isRemote = spanContext.isRemote();
    }

    @Override
    public DDId getTraceId() {
      return traceId;
    }

    @Override
    public DDId getSpanId() {
      return spanId;
    }

    @Override
    public boolean isRemote() {
      return isRemote;
    }

    @Override
    public AgentTrace getTrace() {
      return null;
    }

    @Override
    public Iterable<Map.Entry<String, String>> baggageItems() {
      return null;
    }
  }
}
