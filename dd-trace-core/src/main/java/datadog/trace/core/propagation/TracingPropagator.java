package datadog.trace.core.propagation;

import static datadog.trace.api.ProductTraceSource.UNSET;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromSpanContext;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Propagator;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.HttpCodec.Extractor;
import datadog.trace.core.propagation.HttpCodec.Injector;
import javax.annotation.ParametersAreNonnullByDefault;

/** Propagator for tracing concern. */
@ParametersAreNonnullByDefault
public class TracingPropagator implements Propagator {
  private final boolean enabled;
  private final Injector injector;
  private final Extractor extractor;

  /**
   * Constructor.
   *
   * @param enabled Whether APM tracing is enabled.
   * @param injector The {@link Injector} used for tracing context injection.
   * @param extractor The {@link Extractor} used for tracing context extraction.
   */
  public TracingPropagator(boolean enabled, Injector injector, Extractor extractor) {
    this.enabled = enabled;
    this.injector = injector;
    this.extractor = extractor;
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
      // Stop injection if tracing is disabled and tracing span is coming from tracing only
      if (!this.enabled && ddSpanContext.getPropagationTags().getTraceSource() == UNSET) {
        return;
      }
      ddSpanContext.getTraceCollector().setSamplingPriorityIfNecessary();
      this.injector.inject(ddSpanContext, carrier, setter);
    }
  }

  @Override
  public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
    //noinspection ConstantValue
    if (context == null || carrier == null || visitor == null) {
      return context;
    }
    TagContext spanContext = this.extractor.extract(carrier, toContextVisitor(visitor));
    // If the extraction fails, return the original context
    if (spanContext == null) {
      return context;
    }
    // Otherwise, append a fake span wrapper to context
    return context.with(fromSpanContext(spanContext));
  }

  private static <C> AgentPropagation.ContextVisitor<C> toContextVisitor(
      CarrierVisitor<C> visitor) {
    if (visitor instanceof AgentPropagation.ContextVisitor) {
      return (AgentPropagation.ContextVisitor<C>) visitor;
    }
    return (carrier, classifier) -> visitor.forEachKeyValue(carrier, classifier::accept);
  }
}
