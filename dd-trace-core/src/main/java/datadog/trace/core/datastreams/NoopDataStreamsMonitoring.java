package datadog.trace.core.datastreams;

import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;
import datadog.trace.core.propagation.HttpCodec;
import java.util.LinkedHashMap;

public class NoopDataStreamsMonitoring implements DataStreamsMonitoring {
  @Override
  public void start() {}

  @Override
  public void add(StatsPoint statsPoint) {}

  @Override
  public PathwayContext newPathwayContext() {
    return AgentTracer.NoopPathwayContext.INSTANCE;
  }

  @Override
  public HttpCodec.Extractor decorate(HttpCodec.Extractor extractor) {
    return extractor;
  }

  @Override
  public void mergePathwayContextIntoSpan(AgentSpan span, DataStreamsContextCarrier carrier) {}

  @Override
  public void trackBacklog(LinkedHashMap<String, String> sortedTags, long value) {}

  @Override
  public void close() {}

  @Override
  public void clear() {}
}
