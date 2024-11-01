package datadog.trace.core.datastreams;

import static datadog.trace.api.DDTags.PATHWAY_HASH;
import static datadog.trace.bootstrap.instrumentation.api.PathwayContext.PROPAGATION_KEY_BASE64;

import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

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

    long pathwayHash = pathwayContext.getHash();

    // Extract transaction ID from the carrier
    String transactionId = null;
    if (carrier instanceof DataStreamsContextCarrier) {
      DataStreamsContextCarrier dsCarrier = (DataStreamsContextCarrier) carrier;
      for (Map.Entry<String, Object> entry : dsCarrier.entries()) {
        if ("transaction.id".equals(entry.getKey())) {
          transactionId = entry.getValue().toString();
          break;
        }
      }
    }

    if (transactionId != null && pathwayHash != 0) {
      // report transaction here
      dataStreamsMonitoring.reportTransaction(transactionId, pathwayHash); // adds another piece of logic to transaction thread, finds an active bucket and extend data streams payload
      // need to add logic in writer to actually write it to the payload,
      // have to add logic to accumulate those transaction IDs over time
      // maybe keep transaction IDs in memory, then when we go to write we compress using zip or something
      // background thread flushes it same way it flushes states
      // in this way, actual datapoint
      // follow similar logic to set consume checkpoint
      // create a new thread that flushes to same endpoint and statspayload but stats points are empty backlogs are empty but transactions info is not empty
      // create a parallel thing so easier to split endpoints, two separate queues, two diff endpoint buckets etc.
    }


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
