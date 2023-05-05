package datadog.trace.instrumentation.opentelemetry14;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTrace;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import java.util.Map;

class OtelExtractedContext implements AgentSpan.Context {
  private final DDTraceId traceId;
  private final long spanId;
  private final int prioritySampling;

  private OtelExtractedContext(SpanContext context) {
    this.traceId = DDTraceId.fromHex(context.getTraceId());
    this.spanId = DDSpanId.fromHex(context.getSpanId());
    this.prioritySampling =
        context.isSampled() ? PrioritySampling.SAMPLER_KEEP : PrioritySampling.UNSET;
  }

  static AgentSpan.Context extract(Context context) {
    Span span = Span.fromContext(context);
    SpanContext spanContext = span.getSpanContext();
    if (spanContext instanceof OtelSpanContext) {
      return ((OtelSpanContext) spanContext).delegate;
    } else {
      try {
        return new OtelExtractedContext(spanContext);
      } catch (NumberFormatException e) {
        return AgentTracer.NoopContext.INSTANCE;
      }
    }
  }

  @Override
  public DDTraceId getTraceId() {
    return this.traceId;
  }

  @Override
  public long getSpanId() {
    return this.spanId;
  }

  @Override
  public AgentTrace getTrace() {
    return AgentTracer.NoopAgentTrace.INSTANCE;
  }

  @Override
  public int getSamplingPriority() {
    return this.prioritySampling;
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return null;
  }

  @Override
  public PathwayContext getPathwayContext() {
    return null;
  }
}
