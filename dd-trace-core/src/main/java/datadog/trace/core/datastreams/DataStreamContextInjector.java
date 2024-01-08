package datadog.trace.core.datastreams;

import static datadog.trace.api.DDTags.PATHWAY_HASH;
import static datadog.trace.bootstrap.instrumentation.api.PathwayContext.PROPAGATION_KEY_BASE64;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import java.io.IOException;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataStreamContextInjector {
  private static final Logger LOGGER = LoggerFactory.getLogger(DataStreamContextInjector.class);
  private final DataStreamsMonitoring dataStreamsMonitoring;

  public DataStreamContextInjector(DataStreamsMonitoring dataStreamsMonitoring) {
    this.dataStreamsMonitoring = dataStreamsMonitoring;
  }

  public <C> void injectPathwayContext(
      AgentSpan span,
      C carrier,
      AgentPropagation.Setter<C> setter,
      LinkedHashMap<String, String> sortedTags) {
    injectPathwayContext(span, carrier, setter, sortedTags, 0, 0, true);
  }

  public <C> void injectPathwayContext(
      AgentSpan span,
      C carrier,
      AgentPropagation.Setter<C> setter,
      LinkedHashMap<String, String> sortedTags,
      long defaultTimestamp,
      long payloadSizeBytes) {
    injectPathwayContext(
        span, carrier, setter, sortedTags, defaultTimestamp, payloadSizeBytes, true);
  }

  /** Same as injectPathwayContext, but the stats collected in the StatsPoint are not sent. */
  public <C> void injectPathwayContextWithoutSendingStats(
      AgentSpan span,
      C carrier,
      AgentPropagation.Setter<C> setter,
      LinkedHashMap<String, String> sortedTags) {
    injectPathwayContext(span, carrier, setter, sortedTags, 0, 0, false);
  }

  private <C> void injectPathwayContext(
      AgentSpan span,
      C carrier,
      AgentPropagation.Setter<C> setter,
      LinkedHashMap<String, String> sortedTags,
      long defaultTimestamp,
      long payloadSizeBytes,
      boolean sendCheckpoint) {
    PathwayContext pathwayContext = span.context().getPathwayContext();
    if (pathwayContext == null
        || (span.traceConfig() != null && !span.traceConfig().isDataStreamsEnabled())) {
      return;
    }
    pathwayContext.setCheckpoint(
        sortedTags,
        sendCheckpoint ? dataStreamsMonitoring::add : pathwayContext::saveStats,
        defaultTimestamp,
        payloadSizeBytes);

    boolean injected =
        setter instanceof AgentPropagation.BinarySetter
            ? injectBinaryPathwayContext(
                pathwayContext, carrier, (AgentPropagation.BinarySetter<C>) setter)
            : injectPathwayContext(pathwayContext, carrier, setter);

    if (injected && pathwayContext.getHash() != 0) {
      span.setTag(PATHWAY_HASH, Long.toUnsignedString(pathwayContext.getHash()));
    }
  }

  private static <C> boolean injectBinaryPathwayContext(
      PathwayContext pathwayContext, C carrier, AgentPropagation.BinarySetter<C> setter) {
    try {
      byte[] encodedContext = pathwayContext.encode();
      if (encodedContext != null) {
        LOGGER.debug("Injecting binary pathway context {}", pathwayContext);
        setter.set(carrier, PROPAGATION_KEY_BASE64, encodedContext);
        return true;
      }
    } catch (IOException e) {
      LOGGER.debug("Unable to set encode pathway context", e);
    }
    return false;
  }

  private static <C> boolean injectPathwayContext(
      PathwayContext pathwayContext, C carrier, AgentPropagation.Setter<C> setter) {
    try {
      String encodedContext = pathwayContext.strEncode();
      if (encodedContext != null) {
        LOGGER.debug("Injecting pathway context {}", pathwayContext);
        setter.set(carrier, PROPAGATION_KEY_BASE64, encodedContext);
        return true;
      }
    } catch (IOException e) {
      LOGGER.debug("Unable to set encode pathway context", e);
    }
    return false;
  }
}
