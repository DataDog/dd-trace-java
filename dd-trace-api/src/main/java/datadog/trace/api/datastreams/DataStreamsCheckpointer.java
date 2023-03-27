package datadog.trace.api.datastreams;

public interface DataStreamsCheckpointer {
  <T> void setConsumeCheckpoint(String type, String source, DataStreamsContextCarrier setter);
  <T> void setProduceCheckpoint(String type, String source, DataStreamsContextCarrier setter);
}
