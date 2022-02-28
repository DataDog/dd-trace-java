package datadog.trace.core.datastreams;

import datadog.trace.api.function.Consumer;

public interface PathwayContextHolder {
  PathwayContext getPathwayContext();

  PathwayContext getOrCreatePathwayContext(String type, String group, Consumer<StatsPoint> pointConsumer);

  void setPathwayContext(PathwayContext pathwayContext);
}
