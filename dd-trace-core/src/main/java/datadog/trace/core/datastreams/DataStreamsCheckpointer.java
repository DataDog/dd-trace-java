package datadog.trace.core.datastreams;

public interface DataStreamsCheckpointer {
  void setDataStreamCheckpoint(String edgeName, PathwayContextHolder holder);
}
