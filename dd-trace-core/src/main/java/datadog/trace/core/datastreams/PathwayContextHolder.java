package datadog.trace.core.datastreams;

public interface PathwayContextHolder {
  PathwayContext getPathwayContext();

  PathwayContext getOrCreatePathwayContext();

  void setPathwayContext(PathwayContext pathwayContext);
}
