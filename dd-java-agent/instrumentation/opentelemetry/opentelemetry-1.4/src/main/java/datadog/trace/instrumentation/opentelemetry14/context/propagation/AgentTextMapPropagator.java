package datadog.trace.instrumentation.opentelemetry14.context.propagation;

import static datadog.trace.api.TracePropagationStyle.TRACECONTEXT;
import static datadog.trace.instrumentation.opentelemetry14.trace.OtelSpanContext.fromRemote;

import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context.Extracted;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.instrumentation.opentelemetry14.context.OtelContext;
import datadog.trace.instrumentation.opentelemetry14.trace.OtelExtractedContext;
import datadog.trace.instrumentation.opentelemetry14.trace.OtelSpan;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
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
      TraceState traceState = extractTraceState(extracted, carrier, getter);
      SpanContext spanContext = fromRemote(extracted, traceState);
      return new OtelContext(Span.wrap(spanContext), OtelSpan.invalid());
    }
  }

  /**
   * Extracts tracestate if {@code tracestate} header is present and extracted context comes from
   * {@link TracePropagationStyle#TRACECONTEXT}
   *
   * @param extracted The extracted context.
   * @param carrier The context carrier.
   * @param getter The context getter.
   * @return The extracted tracestate, or an empty tracestate otherwise.
   * @param <C> The carrier type.
   */
  private static <C> TraceState extractTraceState(
      Extracted extracted, C carrier, TextMapGetter<C> getter) {
    String header;
    return extracted instanceof TagContext
            && TRACECONTEXT.equals(((TagContext) extracted).getPropagationStyle())
            && (header = getter.get(carrier, "tracestate")) != null
        ? TraceStateHelper.decodeHeader(header)
        : TraceState.getDefault();
  }
}
