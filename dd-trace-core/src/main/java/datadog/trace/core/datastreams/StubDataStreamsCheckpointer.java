package datadog.trace.core.datastreams;

import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;

public class StubDataStreamsCheckpointer implements DataStreamsCheckpointer {
  @Override
  public void accept(StatsPoint statsPoint) {}

  @Override
  public PathwayContext newPathwayContext() {
    return StubPathwayContext.INSTANCE;
  }
}
