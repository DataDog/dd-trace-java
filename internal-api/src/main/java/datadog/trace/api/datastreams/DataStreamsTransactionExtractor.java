package datadog.trace.api.datastreams;

public interface DataStreamsTransactionExtractor {
  String getName();

  String getType();

  String getValue();
}
