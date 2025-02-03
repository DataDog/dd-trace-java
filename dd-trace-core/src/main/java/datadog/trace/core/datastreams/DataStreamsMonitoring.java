package datadog.trace.core.datastreams;

import datadog.context.propagation.Propagator;
import datadog.trace.api.datastreams.AgentDataStreamsMonitoring;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.core.propagation.HttpCodec;

public interface DataStreamsMonitoring extends AgentDataStreamsMonitoring, AutoCloseable {
  void start();

  /**
   * Gets the propagator for DSM concern.
   *
   * @return The propagator for DSM concern.
   */
  Propagator propagator();

  /**
   * Get a context extractor that support {@link PathwayContext} extraction.
   *
   * @param delegate The extractor to delegate the common trace context extraction.
   * @return An extractor with DSM context extraction.
   */
  HttpCodec.Extractor extractor(HttpCodec.Extractor delegate);

  /**
   * Gets a context injector to propagate {@link PathwayContext}.
   *
   * @return A context injector if supported, {@code null} otherwise.
   */
  DataStreamContextInjector injector();

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
