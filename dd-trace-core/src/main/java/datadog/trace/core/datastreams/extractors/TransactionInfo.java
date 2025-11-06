package datadog.trace.core.datastreams.extractors;

public final class TransactionInfo {
  private final String id;
  private final Long timestamp;

  public TransactionInfo(String id, Long timestamp) {
    this.id = id;
    this.timestamp = timestamp;
  }

  public String getId() {
    return id;
  }

  public Long getTimestamp() {
    return timestamp;
  }
}
