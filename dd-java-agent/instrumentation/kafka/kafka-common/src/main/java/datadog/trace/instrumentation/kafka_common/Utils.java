package datadog.trace.instrumentation.kafka_common;

import datadog.trace.api.datastreams.DataStreamsTransactionTracker;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

public final class Utils {
  private Utils() {} // prevent instantiation

  /**
   * Builds a span-shaped carrier for a {@link PathwayContext} only, without creating a real trace:
   * no sampling, no trace-collector registration, never written to the agent. Used when APM tracing
   * is disabled for the integration but DSM is enabled, so a pathway can still be
   * propagated/checkpointed through the normal active-span-based DSM APIs.
   */
  public static AgentSpan newPathwayOnlySpan(AgentSpanContext extractedContext) {
    PathwayContext pathwayContext =
        extractedContext == null ? null : extractedContext.getPathwayContext();
    if (pathwayContext == null) {
      pathwayContext = AgentTracer.get().getDataStreamsMonitoring().newPathwayContext();
    }
    return AgentSpan.fromSpanContext(new TagContext().withPathwayContext(pathwayContext));
  }

  public static DataStreamsTransactionTracker.TransactionSourceReader
      DSM_TRANSACTION_SOURCE_READER =
          (source, headerName) -> {
            try {
              return new String(((Headers) source).lastHeader(headerName).value());
            } catch (Throwable ignored) {
              return null;
            }
          };

  // this method is used in kafka-clients and kafka-streams instrumentations
  public static long computePayloadSizeBytes(ConsumerRecord<?, ?> val) {
    long headersSize = 0;
    Headers headers = val.headers();
    if (headers != null)
      for (Header h : headers) {
        int valueSize = h.value() == null ? 0 : h.value().length;
        headersSize += valueSize + h.key().getBytes(StandardCharsets.UTF_8).length;
      }
    return headersSize + val.serializedKeySize() + val.serializedValueSize();
  }
}
