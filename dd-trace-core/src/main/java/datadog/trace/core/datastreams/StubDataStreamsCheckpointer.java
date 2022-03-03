package datadog.trace.core.datastreams;

import datadog.trace.api.function.Consumer;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;

public class StubDataStreamsCheckpointer implements Consumer<StatsPoint> {
  @Override
  public void accept(StatsPoint statsPoint) {}
}
