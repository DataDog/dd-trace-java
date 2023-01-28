package datadog.trace.core.datastreams;

public class TopicPartition {
  public String getTopic() {
    return this.topic;
  }

  public int getPartition() {
    return this.partition;
  }

  private final String topic;
  private final int partition;

  public TopicPartition(String topic, int partition) {
    this.topic = topic;
    this.partition = partition;
  }

  @Override
  public int hashCode() {
    return partition + topic.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof TopicPartition
        && this.partition == ((TopicPartition) obj).partition
        && this.topic.equals(((TopicPartition) obj).topic);
  }
}
