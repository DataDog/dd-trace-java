package datadog.trace.bootstrap.instrumentation.api;

public interface PathwayContextHolder {
  ContextKey<PathwayContextHolder> CONTEXT_KEY = ContextKey.named("dd-pathway-context");

  PathwayContext getPathwayContext();

  void mergePathwayContext(PathwayContext pathwayContext);
}
