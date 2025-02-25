package datadog.trace.core.datastreams;

import datadog.context.propagation.Propagator;
import datadog.trace.api.datastreams.AgentDataStreamsMonitoring;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;

public interface DataStreamsMonitoring extends AgentDataStreamsMonitoring, AutoCloseable {
  void start();

  /**
   * Gets the propagator for DSM concern.
   *
   * @return The propagator for DSM concern.
   */
  Propagator propagator();

  /**
   * Injects DSM {@link PathwayContext} into a span {@link AgentSpanContext}.
   *
   * @param span The span to update.
   * @param carrier The carrier of the {@link PathwayContext} to extract and inject.
   */
  void mergePathwayContextIntoSpan(AgentSpan span, DataStreamsContextCarrier carrier);

  void clear();

  @Override
  void close();
}
