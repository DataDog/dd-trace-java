package datadog.trace.api.datastreams;

public final class DataStreamsTransactionExtractor {
  private final String name;
  private final String type;
  private final String value;

  public DataStreamsTransactionExtractor(final String name, final String type, final String value) {
    this.name = name;
    this.type = type;
    this.value = value;
  }

  public String getName() {
    return name;
  }

  public String getType() {
    return type;
  }

  public String getValue() {
    return value;
  }
}
