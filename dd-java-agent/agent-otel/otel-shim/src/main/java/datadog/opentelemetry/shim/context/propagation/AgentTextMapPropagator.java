package datadog.opentelemetry.shim.context.propagation;

import static datadog.opentelemetry.shim.trace.OtelSpanContext.fromRemote;
import static datadog.trace.api.TracePropagationStyle.TRACECONTEXT;

import datadog.opentelemetry.shim.context.OtelContext;
import datadog.opentelemetry.shim.trace.OtelExtractedContext;
import datadog.opentelemetry.shim.trace.OtelSpan;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context.Extracted;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.util.PropagationUtils;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.Collection;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class AgentTextMapPropagator implements TextMapPropagator {

  @Override
  public Collection<String> fields() {
    return PropagationUtils.KNOWN_PROPAGATION_HEADERS;
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
