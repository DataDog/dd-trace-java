package datadog.trace.core.datastreams;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;
import java.util.function.Consumer;

public interface DataStreamsCheckpointer extends Consumer<StatsPoint>, AutoCloseable {
  void start();

  PathwayContext newPathwayContext();

  <C> PathwayContext extractBinaryPathwayContext(
      C carrier, AgentPropagation.BinaryContextVisitor<C> getter);

  <C> PathwayContext extractPathwayContext(C carrier, AgentPropagation.ContextVisitor<C> getter);

  @Override
  void close();

  void clear();
}
