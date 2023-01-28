package datadog.trace.core.datastreams;

public class TopicPartitionGroup {
  public String getTopic() {
    return this.topic;
  }

  private final String topic;

  public int getPartition() {
    return this.partition;
  }

  public String getGroup() {
    return this.group;
  }

  private final int partition;
  private final String group;

  public TopicPartitionGroup(String topic, int partition, String group) {
    this.topic = topic;
    this.partition = partition;
    this.group = group;
  }

  @Override
  public int hashCode() {
    return partition + topic.hashCode() + group.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof TopicPartitionGroup
        && this.partition == ((TopicPartitionGroup) obj).partition
        && this.topic == ((TopicPartitionGroup) obj).topic
        && this.group.equals(((TopicPartitionGroup) obj).group);
  }
}
