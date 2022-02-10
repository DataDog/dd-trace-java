package datadog.trace.core.datastreams;

import datadog.trace.api.function.Consumer;

public interface DataStreamsCheckpointer extends Consumer<StatsPoint> {
  void setDataStreamCheckpoint(String edgeName, PathwayContextHolder holder);
}
