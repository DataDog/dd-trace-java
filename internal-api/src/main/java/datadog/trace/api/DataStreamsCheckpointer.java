package datadog.trace.api;

public interface DataStreamsCheckpointer {
  void setDataStreamCheckpoint(String edgeName);
}
