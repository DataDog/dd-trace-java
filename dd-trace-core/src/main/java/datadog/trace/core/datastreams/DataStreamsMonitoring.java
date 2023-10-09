package datadog.trace.core.datastreams;

import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.bootstrap.instrumentation.api.AgentDataStreamsMonitoring;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.core.propagation.HttpCodec;

public interface DataStreamsMonitoring extends AgentDataStreamsMonitoring, AutoCloseable {
  void start();

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
   * Injects DSM {@link PathwayContext} into a span {@link Context}.
   *
   * @param span The span to update.
   * @param carrier The carrier of the {@link PathwayContext} to extract and inject.
   */
  void mergePathwayContextIntoSpan(AgentSpan span, DataStreamsContextCarrier carrier);

  void clear();

  @Override
  void close();
}
