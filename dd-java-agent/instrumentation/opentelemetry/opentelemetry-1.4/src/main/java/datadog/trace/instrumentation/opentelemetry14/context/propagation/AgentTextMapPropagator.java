package datadog.trace.instrumentation.opentelemetry14.context.propagation;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context.Extracted;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.opentelemetry14.context.OtelContext;
import datadog.trace.instrumentation.opentelemetry14.trace.OtelExtractedContext;
import datadog.trace.instrumentation.opentelemetry14.trace.OtelSpan;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.Arrays;
import java.util.Collection;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class AgentTextMapPropagator implements TextMapPropagator {
  private final Collection<String> fields;

  public AgentTextMapPropagator() {
    // Cannot suppose the current injector from AgentTracer so declare them all
    this.fields =
        Arrays.asList(
            // W3C headers
            "traceparent",
            "tracestate",
            // DD headers
            "x-datadog-trace-id",
            "x-datadog-parent-id",
            "x-datadog-sampling-priority",
            "x-datadog-origin",
            "x-datadog-tags",
            // B3 single headers
            "X-B3-TraceId",
            "X-B3-SpanId",
            "X-B3-Sampled",
            // B3 multi header
            "b3");
  }

  @Override
  public Collection<String> fields() {
    return this.fields;
  }

  @Override
  public <C> void inject(Context context, @Nullable C carrier, TextMapSetter<C> setter) {
    if (carrier == null) {
      return;
    }
    Span span = Span.fromContext(context);
    if (span.getSpanContext().isValid()) {
      AgentSpan.Context agentSpanContext = OtelExtractedContext.extract(context);
      AgentTracer.propagate().inject(agentSpanContext, carrier, setter::set);
    }
  }

  @Override
  public <C> Context extract(Context context, @Nullable C carrier, TextMapGetter<C> getter) {
    if (carrier == null) {
      return context;
    }
    Extracted extracted =
        AgentTracer.propagate()
            .extract(
                carrier,
                (carrier1, classifier) -> {
                  for (String key : getter.keys(carrier1)) {
                    classifier.accept(key, getter.get(carrier1, key));
                  }
                });
    if (extracted == null) {
      return context;
    } else {
      SpanContext spanContext = extractSpanContext(extracted);
      return new OtelContext(Span.wrap(spanContext), OtelSpan.invalid());
    }
  }

  private static SpanContext extractSpanContext(Extracted extracted) {
    String traceId = extracted.getTraceId().toHexString();
    String spanId = SpanId.fromLong(extracted.getSpanId());
    TraceFlags traceFlags =
        extracted.getSamplingPriority() > 0 ? TraceFlags.getSampled() : TraceFlags.getDefault();
    // Do not support tracestate extraction from PropagationTags:
    // As OtelSpanContext used in OtelSpanBuilder only implements AgentSpan.Context,
    // PropagationTags from ExtractedContext are lost.
    TraceState traceState = TraceState.getDefault();
    return SpanContext.createFromRemoteParent(traceId, spanId, traceFlags, traceState);
  }
}
