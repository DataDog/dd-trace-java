package datadog.opentelemetry.shim.trace;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTraceCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OtelExtractedContext implements AgentSpanContext {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelExtractedContext.class);
  private final DDTraceId traceId;
  private final long spanId;
  private final int prioritySampling;

  private OtelExtractedContext(SpanContext context) {
    this.traceId = DDTraceId.fromHex(context.getTraceId());
    this.spanId = DDSpanId.fromHex(context.getSpanId());
    this.prioritySampling =
        context.isSampled() ? PrioritySampling.SAMPLER_KEEP : PrioritySampling.UNSET;
  }

  public static AgentSpanContext extract(Context context) {
    Span span = Span.fromContext(context);
    if (span instanceof OtelSpan) {
      // avoid creating unnecessary OtelSpanContext
      return ((OtelSpan) span).getAgentSpanContext();
    }
    SpanContext spanContext = span.getSpanContext();
    if (spanContext instanceof OtelSpanContext) {
      return ((OtelSpanContext) spanContext).delegate;
    } else if (spanContext.isValid()) {
      try {
        return new OtelExtractedContext(spanContext);
      } catch (NumberFormatException e) {
        LOGGER.debug(
            "Failed to convert span context with trace id = {} and span id = {}",
            spanContext.getTraceId(),
            spanContext.getSpanId());
      }
    }
    return null;
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
  public AgentTraceCollector getTraceCollector() {
    return AgentTracer.NoopAgentTraceCollector.INSTANCE;
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

  @Override
  public boolean isRemote() {
    return true;
  }
}
