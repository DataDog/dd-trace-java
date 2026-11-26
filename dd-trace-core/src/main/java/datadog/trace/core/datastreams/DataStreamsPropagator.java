package datadog.trace.core.datastreams;

import static datadog.context.Context.root;
import static datadog.trace.api.datastreams.PathwayContext.PROPAGATION_KEY_BASE64;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Propagator;
import datadog.trace.api.datastreams.BinaryCarrierSetter;
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
  // Non-null only in Data Streams lite mode
  private final PathwaySeenSet seenSet;
  private final boolean pathwayHashSampledOnly;
  // Non-null only in lite mode with an origin cap
  private final OriginSampler originSampler;

  public DataStreamsPropagator(
      DataStreamsMonitoring dataStreamsMonitoring,
      TimeSource timeSource,
      ThreadLocal<String> serviceNameOverride) {
    this(dataStreamsMonitoring, timeSource, serviceNameOverride, null);
  }

  DataStreamsPropagator(
      DataStreamsMonitoring dataStreamsMonitoring,
      TimeSource timeSource,
      ThreadLocal<String> serviceNameOverride,
      PathwaySeenSet seenSet) {
    this(dataStreamsMonitoring, timeSource, serviceNameOverride, seenSet, false);
  }

  DataStreamsPropagator(
      DataStreamsMonitoring dataStreamsMonitoring,
      TimeSource timeSource,
      ThreadLocal<String> serviceNameOverride,
      PathwaySeenSet seenSet,
      boolean pathwayHashSampledOnly) {
    this(
        dataStreamsMonitoring,
        timeSource,
        serviceNameOverride,
        seenSet,
        pathwayHashSampledOnly,
        null);
  }

  DataStreamsPropagator(
      DataStreamsMonitoring dataStreamsMonitoring,
      TimeSource timeSource,
      ThreadLocal<String> serviceNameOverride,
      PathwaySeenSet seenSet,
      boolean pathwayHashSampledOnly,
      OriginSampler originSampler) {
    this.originSampler = originSampler;
    this.dataStreamsMonitoring = dataStreamsMonitoring;
    this.timeSource = timeSource;
    this.serviceNameOverride = serviceNameOverride;
    this.seenSet = seenSet;
    this.pathwayHashSampledOnly = pathwayHashSampledOnly;
  }

  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
    // TODO Pathway context needs to be stored into its own context element instead of span context
    AgentSpan span;
    PathwayContext pathwayContext;
    DataStreamsContext dsmContext;
    if ((span = AgentSpan.fromContext(context)) == null
        || (pathwayContext = span.spanContext().getPathwayContext()) == null
        || (dsmContext = DataStreamsContext.fromContext(context)) == null
        || !traceConfig().isDataStreamsEnabled()) {
      return;
    }

    if (pathwayContext == UnsampledPathwayContext.INSTANCE) {
      injectPathwayContext(pathwayContext, carrier, setter);
      return;
    }

    if (originSampler != null && !pathwayContext.isStarted()) {
      // A message with no incoming context starts a pathway only if the origin budget allows it.
      // The new context is not stored on the span, so each origin checkpoint has parent 0, which
      // is what loop protection gives repeated produces from one span in full mode.
      if (!originSampler.sample(dsmContext.tags().getHash(), timeSource.getNanoTicks())) {
        // Tells downstream services this message is on a pathway, so they don't start their own
        injectPathwayContext(UnsampledPathwayContext.INSTANCE, carrier, setter);
        return;
      }
      pathwayContext = new DefaultPathwayContext(timeSource, serviceNameOverride.get(), seenSet);
    }

    // Lite never saves the point for PayloadSizeAdvice: the saved point is not cleared, so every
    // later record produced under the same span would resend it.
    Consumer<StatsPoint> pointConsumer =
        dsmContext.sendCheckpoint() || seenSet != null
            ? this.dataStreamsMonitoring::add
            : pathwayContext::saveStats;
    pathwayContext.setCheckpoint(dsmContext, pointConsumer);
    boolean injected = injectPathwayContext(pathwayContext, carrier, setter);
    if (injected) {
      PathwayHashTag.set(span, pathwayContext.getHash(), pathwayHashSampledOnly);
    }
  }

  @SuppressWarnings("unchecked")
  private <C> boolean injectPathwayContext(
      PathwayContext pathwayContext, C carrier, CarrierSetter<C> setter) {
    try {
      byte[] encodedContext = pathwayContext.encodeBytes();
      if (encodedContext != null) {
        if (setter instanceof BinaryCarrierSetter) {
          ((BinaryCarrierSetter<C>) setter)
              .setBytes(carrier, PROPAGATION_KEY_BASE64, encodedContext);
        } else {
          setter.set(carrier, PROPAGATION_KEY_BASE64, new String(encodedContext, ISO_8859_1));
        }
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
        && (extractedSpanContext = extractedSpan.spanContext()) instanceof TagContext) {
      return (TagContext) extractedSpanContext;
    }
    return null;
  }

  private <C> PathwayContext extractDsmPathwayContext(C carrier, CarrierVisitor<C> visitor) {
    return DefaultPathwayContext.extract(
        carrier, visitor, this.timeSource, serviceNameOverride.get(), seenSet);
  }
}
