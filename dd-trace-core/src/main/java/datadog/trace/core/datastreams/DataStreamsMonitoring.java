package datadog.trace.core.datastreams;

import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.bootstrap.instrumentation.api.AgentDataStreamsMonitoring;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;
import datadog.trace.core.propagation.HttpCodec;

public interface DataStreamsMonitoring extends AgentDataStreamsMonitoring, AutoCloseable {
  void start();

  PathwayContext newPathwayContext();

  /**
   * Adds DSM context extractor behavior.
   *
   * @param extractor The extractor to decorate with DSM extraction.
   * @return An extractor with DSM context extraction.
   */
  HttpCodec.Extractor decorate(HttpCodec.Extractor extractor);

  /**
   * Injects DSM {@link PathwayContext} into a span {@link Context}.
   *
   * @param span The span to update.
   * @param carrier The carrier of the {@link PathwayContext} to extract and inject.
   */
  void mergePathwayContextIntoSpan(AgentSpan span, DataStreamsContextCarrier carrier);

  void add(StatsPoint statsPoint);

  void clear();

  @Override
  void close();
}
