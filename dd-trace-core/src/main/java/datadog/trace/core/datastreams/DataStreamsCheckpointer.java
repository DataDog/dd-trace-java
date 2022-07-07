package datadog.trace.core.datastreams;

import datadog.trace.api.function.Consumer;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;

public interface DataStreamsCheckpointer extends Consumer<StatsPoint>, AutoCloseable {
  void start();

  PathwayContext newPathwayContext();

  <C> PathwayContext extractPathwayContext(C carrier, AgentPropagation.ContextVisitor<C> getter);

  @Override
  void close();
}
