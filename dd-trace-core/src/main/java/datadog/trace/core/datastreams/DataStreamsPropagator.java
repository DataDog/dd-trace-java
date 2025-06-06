package datadog.trace.core.datastreams;

import static datadog.trace.api.DDTags.PATHWAY_HASH;
import static datadog.trace.api.datastreams.PathwayContext.PROPAGATION_KEY_BASE64;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Propagator;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class DataStreamsPropagator implements Propagator {
  private final DataStreamsMonitoring dataStreamsMonitoring;
  private final Supplier<TraceConfig> traceConfigSupplier;
  private final TimeSource timeSource;
  private final long hashOfKnownTags;
  private final ThreadLocal<String> serviceNameOverride;

  public DataStreamsPropagator(
      DataStreamsMonitoring dataStreamsMonitoring,
      Supplier<TraceConfig> traceConfigSupplier,
      TimeSource timeSource,
      long hashOfKnownTags,
      ThreadLocal<String> serviceNameOverride) {
    this.dataStreamsMonitoring = dataStreamsMonitoring;
    this.traceConfigSupplier = traceConfigSupplier;
    this.timeSource = timeSource;
    this.hashOfKnownTags = hashOfKnownTags;
    this.serviceNameOverride = serviceNameOverride;
  }

  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
    // TODO Pathway context needs to be stored into its own context element instead of span context
    AgentSpan span;
    PathwayContext pathwayContext;
    DataStreamsContext dsmContext;
    TraceConfig traceConfig;
    if ((span = AgentSpan.fromContext(context)) == null
        || (pathwayContext = span.context().getPathwayContext()) == null
        || (dsmContext = DataStreamsContext.fromContext(context)) == null
        || (traceConfig = span.traceConfig()) == null
        || !traceConfig.isDataStreamsEnabled()) {
      return;
    }

    Consumer<StatsPoint> pointConsumer =
        dsmContext.sendCheckpoint() ? this.dataStreamsMonitoring::add : pathwayContext::saveStats;
    pathwayContext.setCheckpoint(dsmContext, pointConsumer);
    boolean injected = injectPathwayContext(pathwayContext, carrier, setter);
    if (injected && pathwayContext.getHash() != 0) {
      span.setTag(PATHWAY_HASH, Long.toUnsignedString(pathwayContext.getHash()));
    }
  }

  private <C> boolean injectPathwayContext(
      PathwayContext pathwayContext, C carrier, CarrierSetter<C> setter) {
    try {
      String encodedContext = pathwayContext.encode();
      if (encodedContext != null) {
        // LOGGER.debug("Injecting pathway context {}", pathwayContext);
        setter.set(carrier, PROPAGATION_KEY_BASE64, encodedContext);
        return true;
      }
    } catch (IOException e) {
      // LOGGER.debug("Unable to set encode pathway context", e);
    }
    return false;
  }

  @Override
  public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
    // TODO Pathway context needs to be stored into its own context element instead of span context
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
        carrier, visitor, this.timeSource, this.hashOfKnownTags, serviceNameOverride.get());
  }
}
