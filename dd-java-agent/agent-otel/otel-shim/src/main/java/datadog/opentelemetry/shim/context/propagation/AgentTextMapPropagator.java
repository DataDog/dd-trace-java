package datadog.opentelemetry.shim.context.propagation;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.api.TracePropagationStyle.TRACECONTEXT;

import datadog.opentelemetry.shim.context.OtelContext;
import datadog.opentelemetry.shim.trace.OtelExtractedContext;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext.Extracted;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.util.PropagationUtils;
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
    defaultPropagator().inject(convertContext(context), carrier, setter::set);
  }

  @Override
  public <C> Context extract(Context context, @Nullable C carrier, TextMapGetter<C> getter) {
    if (carrier == null) {
      return context;
    }
    datadog.context.Context extracted =
        defaultPropagator()
            .extract(
                convertContext(context),
                carrier,
                (carrier1, classifier) -> {
                  for (String key : getter.keys(carrier1)) {
                    classifier.accept(key, getter.get(carrier1, key));
                  }
                });
    return new OtelContext(extracted);
  }

  private static datadog.context.Context convertContext(Context context) {
    // Try to get the underlying context when injecting a Datadog context
    if (context instanceof OtelContext) {
      return ((OtelContext) context).asContext();
    }
    // Otherwise, fallback to extracting limited tracing context and recreating an OTel context from
    AgentSpanContext extract = OtelExtractedContext.extract(context);
    return AgentSpan.fromSpanContext(extract);
  }

  /**
   * Extracts tracestate if {@code tracestate} header is present and extracted context comes from
   * {@link TracePropagationStyle#TRACECONTEXT}
   *
   * @param extracted The extracted context.
   * @param carrier The context carrier.
   * @param getter The context getter.
   * @param <C> The carrier type.
   * @return The extracted tracestate, or an empty tracestate otherwise.
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
