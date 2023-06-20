package datadog.trace.core.datastreams;

public enum TimestampType {
  TIMESTAMP_CURRENT("current"),
  TIMESTAMP_ORIGIN("origin"),
  TIMESTAMP_EDGE_START("edge_start"),
  TIMESTAMP_SERVICE_START("service_start");

  private final String value;

  TimestampType(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }
}
