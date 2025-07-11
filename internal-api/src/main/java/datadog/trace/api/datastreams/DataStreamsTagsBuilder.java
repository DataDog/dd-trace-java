package datadog.trace.api.datastreams;

public class DataStreamsTagsBuilder {
  // hash tags
  protected String bus;
  protected DataStreamsTags.Direction direction;
  protected String exchange;
  protected String topic;
  protected String type;
  protected String subscription;
  // additional grouping tags
  protected String datasetName;
  protected String datasetNamespace;
  protected Boolean isManual;
  // informational tags
  protected String group;
  protected String consumerGroup;
  protected Boolean hasRoutingKey;
  protected String kafkaClusterId;
  protected String partition;

  public DataStreamsTagsBuilder withBus(String bus) {
    this.bus = bus;
    return this;
  }

  public DataStreamsTagsBuilder withDirection(DataStreamsTags.Direction direction) {
    this.direction = direction;
    return this;
  }

  public DataStreamsTagsBuilder withExchange(String exchange) {
    this.exchange = exchange;
    return this;
  }

  public DataStreamsTagsBuilder withTopic(String topic) {
    this.topic = topic;
    return this;
  }

  public DataStreamsTagsBuilder withType(String type) {
    this.type = type;
    return this;
  }

  public DataStreamsTagsBuilder withSubscription(String subscription) {
    this.subscription = subscription;
    return this;
  }

  public DataStreamsTagsBuilder withDatasetName(String datasetName) {
    this.datasetName = datasetName;
    return this;
  }

  public DataStreamsTagsBuilder withDatasetNamespace(String datasetNamespace) {
    this.datasetNamespace = datasetNamespace;
    return this;
  }

  public DataStreamsTagsBuilder withManual(Boolean isManual) {
    this.isManual = isManual;
    return this;
  }

  public DataStreamsTagsBuilder withGroup(String group) {
    this.group = group;
    return this;
  }

  public DataStreamsTagsBuilder withConsumerGroup(String consumerGroup) {
    this.consumerGroup = consumerGroup;
    return this;
  }

  public DataStreamsTagsBuilder withHasRoutingKey(Boolean hasRoutingKey) {
    this.hasRoutingKey = hasRoutingKey;
    return this;
  }

  public DataStreamsTagsBuilder withKafkaClusterId(String kafkaClusterId) {
    this.kafkaClusterId = kafkaClusterId;
    return this;
  }

  public DataStreamsTagsBuilder withPartition(String partition) {
    this.partition = partition;
    return this;
  }

  public DataStreamsTags build() {
    return new DataStreamsTags(this);
  }
}
