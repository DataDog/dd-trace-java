package datadog.trace.core.propagation;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Propagator;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.core.DDSpanContext;
import javax.annotation.ParametersAreNonnullByDefault;

/**
 * AWS X-Ray Tracking context propagator.
 *
 * <p>Only injection is supported. For full-fledged context propagation support, use the default
 * {@link TracingPropagator} instead.
 *
 * @see XRayHttpCodec
 */
@ParametersAreNonnullByDefault
public class XRayPropagator implements Propagator {
  private final HttpCodec.Injector injector;

  /**
   * Constructor.
   *
   * @param config the config get the baggage mapping from.
   */
  public XRayPropagator(Config config) {
    this.injector = XRayHttpCodec.newInjector(config.getBaggageMapping());
  }

  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
    AgentSpan span;
    //noinspection ConstantValue
    if (context == null
        || carrier == null
        || setter == null
        || (span = fromContext(context)) == null) {
      return;
    }
    AgentSpanContext spanContext = span.context();
    if (spanContext instanceof DDSpanContext) {
      DDSpanContext ddSpanContext = (DDSpanContext) spanContext;
      ddSpanContext.getTraceCollector().setSamplingPriorityIfNecessary();
      this.injector.inject(ddSpanContext, carrier, setter);
    }
  }

  @Override
  public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
    return context;
  }
}
