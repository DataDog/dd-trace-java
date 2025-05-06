package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import java.util.List;
import java.util.Map;

/**
 * Represents Span state that must propagate to descendant Spans and across process boundaries.
 *
 * <p>Span context is logically divided into two pieces: (1) the user-level "Baggage" that
 * propagates across Span boundaries and (2) internal fields that are needed to identify or
 * contextualize the associated Span instance.
 */
public interface AgentSpanContext {

  /**
   * Gets the TraceId of the span's trace.
   *
   * @return The TraceId of the span's trace, or {@link DDTraceId#ZERO} if not set.
   */
  DDTraceId getTraceId();

  /**
   * Gets the SpanId.
   *
   * @return The span identifier, or {@link datadog.trace.api.DDSpanId#ZERO} if not set.
   */
  long getSpanId();

  /**
   * Get the span's trace collector.
   *
   * @return The span's trace, or a noop {@link AgentTracer.NoopAgentTraceCollector#INSTANCE} if the
   *     trace is not valid.
   */
  AgentTraceCollector getTraceCollector();

  /**
   * Gets the trace sampling priority of the span's trace.
   *
   * <p>Check {@link PrioritySampling} for possible values.
   *
   * @return The trace sampling priority of the span's trace, or {@link PrioritySampling#UNSET} if
   *     no priority has been set.
   */
  int getSamplingPriority();

  Iterable<Map.Entry<String, String>> baggageItems();

  PathwayContext getPathwayContext();

  default void mergePathwayContext(PathwayContext pathwayContext) {}

  default void setIntegrationName(CharSequence componentName) {}

  /**
   * Gets whether the span context used is part of the local trace or from another service
   *
   * @return boolean representing if the span context is part of the local trace
   */
  boolean isRemote();

  interface Extracted extends AgentSpanContext {
    /**
     * Gets the span links related to the other terminated context.
     *
     * @return The span links to other extracted contexts found but terminated.
     */
    List<AgentSpanLink> getTerminatedContextLinks();

    String getForwarded();

    String getFastlyClientIp();

    String getCfConnectingIp();

    String getCfConnectingIpv6();

    String getXForwardedProto();

    String getXForwardedHost();

    String getXForwardedPort();

    String getForwardedFor();

    String getXForwardedFor();

    String getXClusterClientIp();

    String getXRealIp();

    String getXClientIp();

    String getUserAgent();

    String getTrueClientIp();

    String getCustomIpHeader();
  }
}
