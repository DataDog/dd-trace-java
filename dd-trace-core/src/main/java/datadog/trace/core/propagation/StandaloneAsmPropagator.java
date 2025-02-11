package datadog.trace.core.propagation;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.TRACING_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Propagator;
import datadog.context.propagation.Propagators;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.core.DDSpanContext;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class StandaloneAsmPropagator implements Propagator {
  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
    // Stop propagation if appsec propagation is disabled (no ASM events), stop propagation
    AgentSpan span;
    if ((span = fromContext(context)) != null) {
      AgentSpanContext spanContext = span.context();
      if (spanContext instanceof DDSpanContext) {
        DDSpanContext ddSpanContext = (DDSpanContext) spanContext;
        if (!ddSpanContext.getPropagationTags().isAppsecPropagationEnabled()) {
          return;
        }
      }
      // Only propagate tracing for appsec standalone product
      Propagators.forConcern(TRACING_CONCERN).inject(span, carrier, setter);
    }
  }

  @Override
  public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
    // Only propagate tracing for appsec standalone product
    return Propagators.forConcern(TRACING_CONCERN).extract(context, carrier, visitor);
  }
}
