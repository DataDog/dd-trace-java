package datadog.trace.core.datastreams;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;

public class StubDataStreamsCheckpointer implements DataStreamsCheckpointer {
  @Override
  public void start() {}

  @Override
  public void accept(StatsPoint statsPoint) {}

  @Override
  public PathwayContext newPathwayContext() {
    return AgentTracer.NoopPathwayContext.INSTANCE;
  }

  @Override
  public <C> PathwayContext extractPathwayContext(
      C carrier, AgentPropagation.ContextVisitor<C> getter) {
    return AgentTracer.NoopPathwayContext.INSTANCE;
  }

  @Override
  public void close() {}
}
