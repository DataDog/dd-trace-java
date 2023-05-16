package datadog.trace.bootstrap.instrumentation.api;

public interface PathwayContextHolder {
  PathwayContext getPathwayContext();

  void mergePathwayContext(PathwayContext pathwayContext);
}
