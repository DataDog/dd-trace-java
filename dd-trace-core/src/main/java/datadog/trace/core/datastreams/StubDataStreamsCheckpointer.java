package datadog.trace.core.datastreams;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;

public class StubDataStreamsCheckpointer implements DataStreamsCheckpointer {
  @Override
  public void accept(StatsPoint statsPoint) {}

  @Override
  public PathwayContext newPathwayContext() {
    return StubPathwayContext.INSTANCE;
  }

  @Override
  public <C> PathwayContext extractPathwayContext(
      C carrier, AgentPropagation.BinaryContextVisitor<C> getter) {
    return StubPathwayContext.INSTANCE;
  }
}
