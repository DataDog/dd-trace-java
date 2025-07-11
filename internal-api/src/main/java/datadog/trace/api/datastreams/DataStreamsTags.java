package datadog.trace.api.datastreams;

import datadog.trace.util.FNV64Hash;

public class DataStreamsTags {
  public enum Direction {
    Unknown,
    Inbound,
    Outbound,
  }

  public enum TagTraverseMode {
    HashOnly,
    GroupOnly,
    ValueOnly,
    All
  }

  public static DataStreamsTags EMPTY = new DataStreamsTagsBuilder().build();

  private final DataStreamsTagsBuilder builder;
  private long hash;
  private long aggregationHash;
  private long completeHash;
  private int size;

  public static final String MANUAL_TAG = "manual_checkpoint";
  public static final String TYPE_TAG = "type";
  public static final String DIRECTION_TAG = "direction";
  public static final String DIRECTION_IN = "in";
  public static final String DIRECTION_OUT = "out";
  public static final String TOPIC_TAG = "topic";
  public static final String BUS_TAG = "bus";
  public static final String PARTITION_TAG = "partition";
  public static final String GROUP_TAG = "group";
  public static final String CONSUMER_GROUP_TAG = "consumer_group";
  public static final String SUBSCRIPTION_TAG = "subscription";
  public static final String EXCHANGE_TAG = "exchange";
  public static final String DATASET_NAME_TAG = "ds.name";
  public static final String DATASET_NAMESPACE_TAG = "ds.namespace";
  public static final String HAS_ROUTING_KEY_TAG = "has_routing_key";
  public static final String KAFKA_CLUSTER_ID_TAG = "kafka_cluster_id";

  public static byte[] longToBytes(long val) {
    return new byte[] {
      (byte) val,
      (byte) (val >> 8),
      (byte) (val >> 16),
      (byte) (val >> 24),
      (byte) (val >> 32),
      (byte) (val >> 40),
      (byte) (val >> 48),
      (byte) (val >> 56)
    };
  }

  public DataStreamsTags(DataStreamsTagsBuilder builder) {
    this.builder = builder;
    this.size =
        this.forEachTag(
            (name, value) -> {
              this.hash =
                  FNV64Hash.continueHash(this.hash, name + ":" + value, FNV64Hash.Version.v1);
            },
            TagTraverseMode.HashOnly);

    this.aggregationHash = this.hash;
    this.size +=
        this.forEachTag(
            (name, value) -> {
              this.aggregationHash =
                  FNV64Hash.continueHash(
                      this.aggregationHash, name + ":" + value, FNV64Hash.Version.v1);
            },
            TagTraverseMode.GroupOnly);

    this.completeHash = aggregationHash;
    this.size +=
        this.forEachTag(
            (name, value) -> {
              this.completeHash =
                  FNV64Hash.continueHash(
                      this.completeHash, name + ":" + value, FNV64Hash.Version.v1);
            },
            TagTraverseMode.ValueOnly);
  }

  public int forEachTag(DataStreamsTagsProcessor processor, TagTraverseMode mode) {
    int count = 0;

    if (mode == TagTraverseMode.HashOnly || mode == TagTraverseMode.All) {
      if (this.builder.bus != null) {
        processor.process(BUS_TAG, this.builder.bus);
        count += 1;
      }

      if (this.builder.direction == Direction.Inbound) {
        count += 1;
        processor.process(DIRECTION_TAG, DIRECTION_IN);
      } else if (this.builder.direction == Direction.Outbound) {
        count += 1;
        processor.process(DIRECTION_TAG, DIRECTION_OUT);
      }

      if (this.builder.exchange != null) {
        count += 1;
        processor.process(EXCHANGE_TAG, this.builder.exchange);
      }

      // topic and type are always required, no need to check for null
      count += 2;
      processor.process(TOPIC_TAG, this.builder.topic);
      processor.process(TYPE_TAG, this.builder.type);

      if (this.builder.subscription != null) {
        count += 1;
        processor.process(SUBSCRIPTION_TAG, this.builder.subscription);
      }
    }

    if (mode == TagTraverseMode.GroupOnly || mode == TagTraverseMode.All) {
      count += 1;
      processor.process(MANUAL_TAG, this.builder.isManual.toString());

      if (this.builder.datasetName != null) {
        count += 1;
        processor.process(DATASET_NAME_TAG, this.builder.datasetName);
      }

      if (this.builder.datasetNamespace != null) {
        count += 1;
        processor.process(DATASET_NAMESPACE_TAG, this.builder.datasetNamespace);
      }
    }

    if (mode == TagTraverseMode.ValueOnly || mode == TagTraverseMode.All) {
      count += 1;
      processor.process(HAS_ROUTING_KEY_TAG, this.builder.hasRoutingKey.toString());

      if (this.builder.consumerGroup != null) {
        count += 1;
        processor.process(CONSUMER_GROUP_TAG, this.builder.consumerGroup);
      }

      if (this.builder.group != null) {
        count += 1;
        processor.process(GROUP_TAG, this.builder.group);
      }

      if (this.builder.kafkaClusterId != null) {
        count += 1;
        processor.process(KAFKA_CLUSTER_ID_TAG, this.builder.kafkaClusterId);
      }
      if (this.builder.partition != null) {
        count += 1;
        processor.process(PARTITION_TAG, this.builder.partition);
      }
    }

    return count;
  }

  public Direction getDirection() {
    return this.builder.direction;
  }

  public String getTopic() {
    return this.builder.topic;
  }

  public String getType() {
    return this.builder.type;
  }

  public Boolean isManual() {
    return this.builder.isManual;
  }

  public String getBus() {
    return this.builder.bus;
  }

  public String getExchange() {
    return this.builder.exchange;
  }

  public String getSubscription() {
    return this.builder.subscription;
  }

  public String getDatasetName() {
    return this.builder.datasetName;
  }

  public String getDatasetNamespace() {
    return this.builder.datasetNamespace;
  }

  public String getGroup() {
    return this.builder.group;
  }

  public String getPartition() {
    return this.builder.partition;
  }

  public String getKafkaClusterId() {
    return this.builder.kafkaClusterId;
  }

  public boolean getHasRoutingKey() {
    return this.builder.hasRoutingKey;
  }

  public long getHash() {
    return hash;
  }

  public long getAggregationHash() {
    return aggregationHash;
  }

  public int getSize() {
    return size;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    DataStreamsTags that = (DataStreamsTags) o;
    return this.completeHash == that.completeHash;
  }

  @Override
  public String toString() {
    return "DataStreamsTags{"
        + "bus='"
        + this.builder.bus
        + ","
        + ", direction="
        + this.builder.direction
        + ","
        + ", exchange='"
        + this.builder.exchange
        + ","
        + ", topic='"
        + this.builder.topic
        + ","
        + ", type='"
        + this.builder.type
        + ","
        + ", subscription='"
        + this.builder.subscription
        + ","
        + ", datasetName='"
        + this.builder.datasetName
        + ","
        + ", datasetNamespace='"
        + this.builder.datasetNamespace
        + ","
        + ", isManual="
        + this.builder.isManual
        + ", group='"
        + this.builder.group
        + ", consumerGroup='"
        + this.builder.consumerGroup
        + ","
        + ", hasRoutingKey='"
        + this.builder.hasRoutingKey
        + ","
        + ", kafkaClusterId='"
        + this.builder.kafkaClusterId
        + ","
        + ", partition='"
        + this.builder.partition
        + ","
        + ", hash="
        + hash
        + ","
        + ", aggregationHash="
        + aggregationHash
        + ","
        + ", size="
        + size;
  }
}
