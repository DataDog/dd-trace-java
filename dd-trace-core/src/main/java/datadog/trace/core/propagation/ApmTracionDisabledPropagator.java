package datadog.trace.core.propagation;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.TRACING_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Propagator;
import datadog.context.propagation.Propagators;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.core.DDSpanContext;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class ApmTracionDisabledPropagator implements Propagator {
  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
    // Stop propagation if  no product events, stop propagation
    AgentSpan span;
    if ((span = fromContext(context)) != null) {
      AgentSpanContext spanContext = span.context();
      if (spanContext instanceof DDSpanContext) {
        DDSpanContext ddSpanContext = (DDSpanContext) spanContext;
        if (ddSpanContext.getPropagationTags().getTraceSource() == ProductTraceSource.UNSET) {
          return;
        }
      }
      // Only propagate tracing if trace source set
      Propagators.forConcern(TRACING_CONCERN).inject(span, carrier, setter);
    }
  }

  @Override
  public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
    // Only propagate tracing if trace source set
    return Propagators.forConcern(TRACING_CONCERN).extract(context, carrier, visitor);
  }
}
