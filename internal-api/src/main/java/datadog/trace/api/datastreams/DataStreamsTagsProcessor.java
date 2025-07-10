package datadog.trace.api.datastreams;

public interface DataStreamsTagsProcessor {
  void process(String name, String value);
}
