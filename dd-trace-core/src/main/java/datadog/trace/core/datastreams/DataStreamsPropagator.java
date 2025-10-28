package datadog.trace.core.datastreams;

import static datadog.context.Context.root;
import static datadog.trace.api.DDTags.PATHWAY_HASH;
import static datadog.trace.api.datastreams.PathwayContext.PROPAGATION_KEY_BASE64;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Propagator;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.io.IOException;
import java.util.function.Consumer;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class DataStreamsPropagator implements Propagator {
  private final DataStreamsMonitoring dataStreamsMonitoring;
  private final TimeSource timeSource;
  private final ThreadLocal<String> serviceNameOverride;

  public DataStreamsPropagator(
      DataStreamsMonitoring dataStreamsMonitoring,
      TimeSource timeSource,
      ThreadLocal<String> serviceNameOverride) {
    this.dataStreamsMonitoring = dataStreamsMonitoring;
    this.timeSource = timeSource;
    this.serviceNameOverride = serviceNameOverride;
  }

  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
    // TODO Pathway context needs to be stored into its own context element instead of span context
    AgentSpan span;
    PathwayContext pathwayContext;
    DataStreamsContext dsmContext;
    if ((span = AgentSpan.fromContext(context)) == null
        || (pathwayContext = span.context().getPathwayContext()) == null
        || (dsmContext = DataStreamsContext.fromContext(context)) == null
        || !traceConfig().isDataStreamsEnabled()) {
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
    PathwayContext pathwayContext;
    // Ensure if DSM is enabled and look for pathway context
    if (traceConfig().isDataStreamsEnabled()
        && (pathwayContext = extractDsmPathwayContext(carrier, visitor)) != null) {
      // Get span context to store pathway context into
      TagContext spanContext = getSpanContextOrNull(context);
      if (spanContext == null) {
        spanContext = new TagContext();
        AgentSpan span = fromSpanContext(spanContext);
        context = root().with(span);
      }
      // Store pathway context into span context
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

  private <C> PathwayContext extractDsmPathwayContext(C carrier, CarrierVisitor<C> visitor) {
    return DefaultPathwayContext.extract(
        carrier, visitor, this.timeSource, serviceNameOverride.get());
  }
}
