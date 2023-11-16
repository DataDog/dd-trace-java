package datadog.trace.core.datastreams;

public class Schema {
  private final long hash;
  private final String definition;
  private final String topic;

  public Schema(long hash, String definition, String topic) {
    this.hash = hash;
    this.definition = definition;
    this.topic = topic;
  }

  public long getHash() {
    return hash;
  }

  public String getDefinition() {
    return definition;
  }

  public String getTopic() {
    return topic;
  }
}
