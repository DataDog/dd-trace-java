package datadog.trace.core.datastreams;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Propagator;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

// TODO Javadoc
@ParametersAreNonnullByDefault
public class DataStreamPropagator implements Propagator {
  private final Supplier<TraceConfig> traceConfigSupplier;
  private final TimeSource timeSource;
  private final long hashOfKnownTags;
  private final String serviceNameOverride;

  public DataStreamPropagator(
      Supplier<TraceConfig> traceConfigSupplier,
      TimeSource timeSource,
      long hashOfKnownTags,
      String serviceNameOverride) {
    this.traceConfigSupplier = traceConfigSupplier;
    this.timeSource = timeSource;
    this.hashOfKnownTags = hashOfKnownTags;
    this.serviceNameOverride = serviceNameOverride;
  }

  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
    // TODO Still in CorePropagation, not migrated yet
  }

  @Override
  public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
    // TODO Pathway context needs to be stored into its own context element
    // Get span context to store pathway context into
    TagContext spanContext = getSpanContextOrNull(context);
    PathwayContext pathwayContext;
    // Ensure if DSM is enabled and look for pathway context
    if (isDsmEnabled(spanContext)
        && (pathwayContext = extractDsmPathwayContext(carrier, visitor)) != null) {
      // Store pathway context into span context
      if (spanContext == null) {
        spanContext = new TagContext();
        AgentSpan span = AgentSpan.fromSpanContext(spanContext);
        context = Context.root().with(span);
      }
      spanContext.withPathwayContext(pathwayContext);
    }
    return context;
  }

  private TagContext getSpanContextOrNull(Context context) {
    AgentSpan extractedSpan = AgentSpan.fromContext(context);
    AgentSpanContext extractedSpanContext;
    if (extractedSpan != null
        && (extractedSpanContext = extractedSpan.context()) instanceof TagContext) {
      return (TagContext) extractedSpanContext;
    }
    return null;
  }

  private boolean isDsmEnabled(@Nullable TagContext tagContext) {
    TraceConfig traceConfig = tagContext == null ? null : tagContext.getTraceConfig();
    if (traceConfig == null) {
      traceConfig = this.traceConfigSupplier.get();
    }
    return traceConfig.isDataStreamsEnabled();
  }

  private <C> PathwayContext extractDsmPathwayContext(C carrier, CarrierVisitor<C> visitor) {
    return DefaultPathwayContext.extract(
        carrier, visitor, this.timeSource, this.hashOfKnownTags, this.serviceNameOverride);
  }
}
