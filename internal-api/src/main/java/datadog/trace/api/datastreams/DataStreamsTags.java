package datadog.trace.api.datastreams;

import datadog.trace.util.FNV64Hash;

import javax.xml.crypto.Data;

public class DataStreamsTags {
  public enum Direction {
    Inbound,
    Outbound,
  }

  public enum TagTraverseMode {
    HashOnly,
    GroupOnly,
    ValueOnly,
    All
  }

  public static DataStreamsTags EMPTY = DataStreamsTags.Create(null, null, null);

  // hash tags
  private final String bus;
  private final Direction direction;
  private final String exchange;
  private final String topic;
  private final String type;
  private final String subscription;
  // additional grouping tags
  private final String datasetName;
  private final String datasetNamespace;
  private final Boolean isManual;
  // informational tags
  private final String group;
  private final Boolean hasRoutingKey;
  private final String kafkaClusterId;
  private final String partition;

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

  public static DataStreamsTags Create(String type, Direction direction, String topic) {
    return DataStreamsTags.Create(type, direction, topic, false);
  }

  public static DataStreamsTags Create(
      String type, Direction direction, String topic, Boolean isManual) {
    return new DataStreamsTags(
        type, direction, topic, isManual, null, null, null, null, null, null, false, null, null);
  }

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

  private DataStreamsTags(
      String type,
      Direction direction,
      String topic,
      Boolean isManual,
      String bus,
      String exchange,
      String subscription,
      String datasetName,
      String datasetNamespace,
      String group,
      Boolean hasRoutingKey,
      String kafkaClusterId,
      String partition) {
    this.bus = bus;
    this.direction = direction;
    this.exchange = exchange;
    this.topic = topic;
    this.type = type;
    this.subscription = subscription;
    this.isManual = isManual;
    this.datasetName = datasetName;
    this.datasetNamespace = datasetNamespace;
    this.group = group;
    this.hasRoutingKey = hasRoutingKey;
    this.kafkaClusterId = kafkaClusterId;
    this.partition = partition;

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
      if (this.bus != null) {
        processor.process(BUS_TAG, this.bus);
        count += 1;
      }

      count += 1;
      if (this.direction == Direction.Inbound) {
        processor.process(DIRECTION_TAG, DIRECTION_IN);
      } else {
        processor.process(DIRECTION_TAG, DIRECTION_OUT);
      }

      if (this.exchange != null) {
        count += 1;
        processor.process(EXCHANGE_TAG, this.exchange);
      }

      // topic and type are always required, no need to check for null
      count += 2;
      processor.process(TOPIC_TAG, this.topic);
      processor.process(TYPE_TAG, this.type);

      if (this.subscription != null) {
        count += 1;
        processor.process(SUBSCRIPTION_TAG, this.subscription);
      }
    }

    if (mode == TagTraverseMode.GroupOnly || mode == TagTraverseMode.All) {
      count += 1;
      processor.process(MANUAL_TAG, this.isManual.toString());

      if (this.datasetName != null) {
        count += 1;
        processor.process(DATASET_NAME_TAG, this.datasetName);
      }

      if (this.datasetNamespace != null) {
        count += 1;
        processor.process(DATASET_NAMESPACE_TAG, this.datasetNamespace);
      }
    }

    if (mode == TagTraverseMode.ValueOnly || mode == TagTraverseMode.All) {
      if (this.hasRoutingKey != null) {
        count += 1;
        processor.process(HAS_ROUTING_KEY_TAG, this.hasRoutingKey.toString());
      }
      if (this.kafkaClusterId != null) {
        count += 1;
        processor.process(KAFKA_CLUSTER_ID_TAG, this.kafkaClusterId);
      }
      if (this.partition != null) {
        count += 1;
        processor.process(PARTITION_TAG, this.partition);
      }
    }

    return count;
  }

  public Direction getDirection() {
    return direction;
  }

  public String getTopic() {
    return topic;
  }

  public String getType() {
    return type;
  }

  public Boolean isManual() {
    return isManual;
  }

  public String getBus() {
    return bus;
  }

  public String getExchange() {
    return exchange;
  }

  public String getSubscription() {
    return subscription;
  }

  public String getDatasetName() {
    return datasetName;
  }

  public String getDatasetNamespace() {
    return datasetNamespace;
  }

  public String getGroup() {
    return group;
  }

  public String getPartition() {
    return partition;
  }

  public String getKafkaClusterId() {
    return kafkaClusterId;
  }

  public boolean getHasRoutingKey() {
    return hasRoutingKey;
  }

  public long getHash() {
    return hash;
  }

  public long getAggregationHash() {
    return aggregationHash;
  }

  public long getCompleteHash() {
    return completeHash;
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
        + bus
        + ","
        + ", direction="
        + direction
        + ","
        + ", exchange='"
        + exchange
        + ","
        + ", topic='"
        + topic
        + ","
        + ", type='"
        + type
        + ","
        + ", subscription='"
        + subscription
        + ","
        + ", datasetName='"
        + datasetName
        + ","
        + ", datasetNamespace='"
        + datasetNamespace
        + ","
        + ", isManual="
        + isManual
        + ", group='"
        + group
        + ","
        + ", hasRoutingKey='"
        + hasRoutingKey
        + ","
        + ", kafkaClusterId='"
        + kafkaClusterId
        + ","
        + ", partition='"
        + partition
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
