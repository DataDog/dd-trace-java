package datadog.trace.api.datastreams;

import datadog.trace.api.BaseHash;
import datadog.trace.util.FNV64Hash;
import java.util.Objects;

public class DataStreamsTags {
  public enum Direction {
    UNKNOWN,
    INBOUND,
    OUTBOUND,
  }

  public static DataStreamsTags EMPTY = DataStreamsTags.create(null, null);

  // Tag positions, in hashing order. WARNING: DO NOT REORDER! The order is part of the hashes, so
  // reordering changes every pathway hash.
  private static final int BUS_INDEX = 0;
  private static final int DIRECTION_INDEX = 1;
  private static final int EXCHANGE_INDEX = 2;
  private static final int TOPIC_INDEX = 3;
  private static final int TYPE_INDEX = 4;
  private static final int SUBSCRIPTION_INDEX = 5;
  private static final int KAFKA_CLUSTER_ID_INDEX = 6;
  private static final int DATASET_NAME_INDEX = 7;
  private static final int DATASET_NAMESPACE_INDEX = 8;
  private static final int MANUAL_INDEX = 9;
  private static final int GROUP_INDEX = 10;
  private static final int CONSUMER_GROUP_INDEX = 11;
  private static final int PARTITION_INDEX = 12;
  private static final int HAS_ROUTING_KEY_INDEX = 13;
  private static final int TAG_COUNT = 14;

  /**
   * Tags from here up to {@link #VALUE_TAGS_START} are aggregation tags; those before, hash tags.
   */
  static final int AGGREGATION_TAGS_START = DATASET_NAME_INDEX;

  /** Tags from here on are values, part only of {@link #equals}. */
  static final int VALUE_TAGS_START = PARTITION_INDEX;

  // Hashes are computed on first read, since with Data Streams off nothing reads them
  private long hash;
  private long aggregationHash;
  private long completeHash;
  private int nonNullSize;
  private volatile boolean hashed;

  // Read when the tags are built, as they depend on the time and thread of the checkpoint
  private final long baseHash;
  private final String serviceNameOverrideValue;

  // Tag values; the "name:value" tags are built from them only when read
  // hash tags
  private final String bus;
  private final Direction directionValue;
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
  private final String consumerGroup;
  private final Boolean hasRoutingKey;
  private final String kafkaClusterId;
  private final String partition;

  public static final String MANUAL_TAG = "manual_checkpoint";
  public static final String TYPE_TAG = "type";
  public static final String DIRECTION_TAG = "direction";
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

  private static volatile ThreadLocal<String> serviceNameOverride;

  public static byte[] longToBytes(long val) {
    return new byte[] {
      (byte) (val >> 56),
      (byte) (val >> 48),
      (byte) (val >> 40),
      (byte) (val >> 32),
      (byte) (val >> 24),
      (byte) (val >> 16),
      (byte) (val >> 8),
      (byte) val
    };
  }

  public static DataStreamsTags create(String type, Direction direction) {
    return DataStreamsTags.create(type, direction, null);
  }

  public static DataStreamsTags create(String type, Direction direction, String topic) {
    return DataStreamsTags.createWithGroup(type, direction, topic, null);
  }

  public static DataStreamsTags createWithSubscription(
      String type, Direction direction, String subscription) {
    return new DataStreamsTags(
        null,
        direction,
        null,
        null,
        type,
        subscription,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static DataStreamsTags create(
      String type, Direction direction, String topic, String group, String kafkaClusterId) {
    return new DataStreamsTags(
        null,
        direction,
        null,
        topic,
        type,
        null,
        null,
        null,
        null,
        group,
        null,
        null,
        kafkaClusterId,
        null);
  }

  public static DataStreamsTags createManual(String type, Direction direction, String topic) {
    return new DataStreamsTags(
        null, direction, null, topic, type, null, null, null, true, null, null, null, null, null);
  }

  public static DataStreamsTags createWithBus(Direction direction, String bus) {
    return new DataStreamsTags(
        bus, direction, null, null, "bus", null, null, null, null, null, null, null, null, null);
  }

  public static DataStreamsTags createWithPartition(
      String type, String topic, String partition, String kafkaClusterId, String consumerGroup) {
    return new DataStreamsTags(
        null,
        null,
        null,
        topic,
        type,
        null,
        null,
        null,
        null,
        null,
        consumerGroup,
        null,
        kafkaClusterId,
        partition);
  }

  /// For usage in tests *only*
  public Boolean hasAllTags(String[] tags) {
    for (String tag : tags) {
      int separator = tag.indexOf(':');
      if (separator == -1) {
        return false;
      }
      int index = indexOfName(tag.substring(0, separator));
      if (index == -1 || !Objects.equals(tagByIndex(index), tag)) {
        return false;
      }
    }

    return true;
  }

  private int indexOfName(String name) {
    for (int i = 0; i < size(); i++) {
      if (nameByIndex(i).equals(name)) {
        return i;
      }
    }
    return -1;
  }

  public static DataStreamsTags createWithGroup(
      String type, Direction direction, String topic, String group) {
    return new DataStreamsTags(
        null, direction, null, topic, type, null, null, null, null, group, null, null, null, null);
  }

  public static DataStreamsTags createWithDataset(
      String type, Direction direction, String topic, String datasetName, String datasetNamespace) {
    return new DataStreamsTags(
        null,
        direction,
        null,
        topic,
        type,
        null,
        datasetName,
        datasetNamespace,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static void setServiceNameOverride(ThreadLocal<String> serviceNameOverride) {
    DataStreamsTags.serviceNameOverride = serviceNameOverride;
  }

  public static DataStreamsTags createWithClusterId(
      String type, Direction direction, String topic, String clusterId) {
    return new DataStreamsTags(
        null, direction, null, topic, type, null, null, null, null, null, null, null, clusterId,
        null);
  }

  public static DataStreamsTags createWithExchange(
      String type, Direction direction, String exchange, Boolean hasRoutingKey) {
    return new DataStreamsTags(
        null,
        direction,
        exchange,
        null,
        type,
        null,
        null,
        null,
        false,
        null,
        null,
        hasRoutingKey,
        null,
        null);
  }

  public DataStreamsTags(
      String bus,
      Direction direction,
      String exchange,
      String topic,
      String type,
      String subscription,
      String datasetName,
      String datasetNamespace,
      Boolean isManual,
      String group,
      String consumerGroup,
      Boolean hasRoutingKey,
      String kafkaClusterId,
      String partition) {
    this.bus = bus;
    this.directionValue = direction;
    this.exchange = exchange;
    this.topic = topic;
    this.type = type;
    this.subscription = subscription;
    this.datasetName = datasetName;
    this.datasetNamespace = datasetNamespace;
    this.isManual = isManual;
    this.group = group;
    this.consumerGroup = consumerGroup;
    this.hasRoutingKey = hasRoutingKey;
    this.kafkaClusterId = kafkaClusterId;
    this.partition = partition;

    this.baseHash = BaseHash.getBaseHash();
    ThreadLocal<String> serviceNameOverride = DataStreamsTags.serviceNameOverride;
    this.serviceNameOverrideValue = serviceNameOverride != null ? serviceNameOverride.get() : null;
  }

  private void ensureHashed() {
    if (hashed) {
      return;
    }
    // Racing threads compute the same values, so the race is benign
    long hash = baseHash;
    if (serviceNameOverrideValue != null) {
      hash = FNV64Hash.continueHash(hash, serviceNameOverrideValue, FNV64Hash.Version.v1);
    }
    int nonNullSize = 0;

    // hashable tags are 0-6: bus, direction, exchange, topic, type, subscription, kafkaClusterId
    for (int i = 0; i < AGGREGATION_TAGS_START; i++) {
      String value = valueByIndex(i);
      if (value != null) {
        nonNullSize++;
        hash = continueHash(hash, i, value);
      }
    }

    // aggregation tags are 7-11: datasetName, datasetNamespace, isManual, group, consumerGroup
    long aggregationHash = hash;
    for (int i = AGGREGATION_TAGS_START; i < VALUE_TAGS_START; i++) {
      String value = valueByIndex(i);
      if (value != null) {
        nonNullSize++;
        aggregationHash = continueHash(aggregationHash, i, value);
      }
    }

    // values are 12-13: partition, hasRoutingKey
    long completeHash = aggregationHash;
    for (int i = VALUE_TAGS_START; i < size(); i++) {
      String value = valueByIndex(i);
      if (value != null) {
        nonNullSize++;
        completeHash = continueHash(completeHash, i, value);
      }
    }

    this.hash = hash;
    this.aggregationHash = aggregationHash;
    this.completeHash = completeHash;
    this.nonNullSize = nonNullSize;
    this.hashed = true;
  }

  /** Continues {@code hash} over the tag {@code "name:value"}, without building it. */
  private static long continueHash(long hash, int index, String value) {
    hash = FNV64Hash.continueHash(hash, nameByIndex(index), FNV64Hash.Version.v1);
    hash = FNV64Hash.continueHash(hash, ":", FNV64Hash.Version.v1);
    return FNV64Hash.continueHash(hash, value, FNV64Hash.Version.v1);
  }

  public int size() {
    return TAG_COUNT;
  }

  /**
   * The {@code name:value} tag at {@code index}, or null if it has no value. Builds a new string on
   * each call, as do the {@code get*} tag getters, so they're meant for serialization and tests,
   * not per-message use.
   */
  public String tagByIndex(int index) {
    String value = valueByIndex(index);
    return value != null ? nameByIndex(index) + ":" + value : null;
  }

  private String valueByIndex(int index) {
    switch (index) {
      case BUS_INDEX:
        return this.bus;
      case DIRECTION_INDEX:
        return directionTagValue();
      case EXCHANGE_INDEX:
        return this.exchange;
      case TOPIC_INDEX:
        return this.topic;
      case TYPE_INDEX:
        return this.type;
      case SUBSCRIPTION_INDEX:
        return this.subscription;
      case KAFKA_CLUSTER_ID_INDEX:
        return this.kafkaClusterId;
      case DATASET_NAME_INDEX:
        return this.datasetName;
      case DATASET_NAMESPACE_INDEX:
        return this.datasetNamespace;
      case MANUAL_INDEX:
        return toString(this.isManual);
      case GROUP_INDEX:
        return this.group;
      case CONSUMER_GROUP_INDEX:
        return this.consumerGroup;
      case PARTITION_INDEX:
        return this.partition;
      case HAS_ROUTING_KEY_INDEX:
        return toString(this.hasRoutingKey);
      default:
        return null;
    }
  }

  private static String nameByIndex(int index) {
    switch (index) {
      case BUS_INDEX:
        return BUS_TAG;
      case DIRECTION_INDEX:
        return DIRECTION_TAG;
      case EXCHANGE_INDEX:
        return EXCHANGE_TAG;
      case TOPIC_INDEX:
        return TOPIC_TAG;
      case TYPE_INDEX:
        return TYPE_TAG;
      case SUBSCRIPTION_INDEX:
        return SUBSCRIPTION_TAG;
      case KAFKA_CLUSTER_ID_INDEX:
        return KAFKA_CLUSTER_ID_TAG;
      case DATASET_NAME_INDEX:
        return DATASET_NAME_TAG;
      case DATASET_NAMESPACE_INDEX:
        return DATASET_NAMESPACE_TAG;
      case MANUAL_INDEX:
        return MANUAL_TAG;
      case GROUP_INDEX:
        return GROUP_TAG;
      case CONSUMER_GROUP_INDEX:
        return CONSUMER_GROUP_TAG;
      case PARTITION_INDEX:
        return PARTITION_TAG;
      case HAS_ROUTING_KEY_INDEX:
        return HAS_ROUTING_KEY_TAG;
      default:
        return null;
    }
  }

  private String directionTagValue() {
    if (directionValue == Direction.INBOUND) {
      return "in";
    } else if (directionValue == Direction.OUTBOUND) {
      return "out";
    }
    return null;
  }

  private static String toString(Boolean value) {
    return value != null ? value.toString() : null;
  }

  public String getDirection() {
    return tagByIndex(DIRECTION_INDEX);
  }

  public String getTopic() {
    return tagByIndex(TOPIC_INDEX);
  }

  public String getType() {
    return tagByIndex(TYPE_INDEX);
  }

  public String getIsManual() {
    return tagByIndex(MANUAL_INDEX);
  }

  public String getBus() {
    return tagByIndex(BUS_INDEX);
  }

  public String getExchange() {
    return tagByIndex(EXCHANGE_INDEX);
  }

  public Direction getDirectionValue() {
    return this.directionValue;
  }

  public String getSubscription() {
    return tagByIndex(SUBSCRIPTION_INDEX);
  }

  public String getDatasetName() {
    return tagByIndex(DATASET_NAME_INDEX);
  }

  public String getDatasetNamespace() {
    return tagByIndex(DATASET_NAMESPACE_INDEX);
  }

  public String getGroup() {
    return tagByIndex(GROUP_INDEX);
  }

  public String getConsumerGroup() {
    return tagByIndex(CONSUMER_GROUP_INDEX);
  }

  public String getPartition() {
    return tagByIndex(PARTITION_INDEX);
  }

  public String getKafkaClusterId() {
    return tagByIndex(KAFKA_CLUSTER_ID_INDEX);
  }

  public String getHasRoutingKey() {
    return tagByIndex(HAS_ROUTING_KEY_INDEX);
  }

  public int nonNullSize() {
    ensureHashed();
    return this.nonNullSize;
  }

  public long getHash() {
    ensureHashed();
    return hash;
  }

  public long getAggregationHash() {
    ensureHashed();
    return aggregationHash;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    DataStreamsTags that = (DataStreamsTags) o;
    ensureHashed();
    that.ensureHashed();
    return this.completeHash == that.completeHash;
  }

  @Override
  public int hashCode() {
    ensureHashed();
    return Long.hashCode(this.completeHash);
  }

  @Override
  public String toString() {
    return "DataStreamsTags{"
        + "bus='"
        + getBus()
        + "', direction='"
        + getDirection()
        + "', exchange='"
        + getExchange()
        + "', topic='"
        + getTopic()
        + "', type='"
        + getType()
        + "', subscription='"
        + getSubscription()
        + "', datasetName='"
        + getDatasetName()
        + "', datasetNamespace='"
        + getDatasetNamespace()
        + "', isManual="
        + getIsManual()
        + "', group='"
        + getGroup()
        + "', consumerGroup='"
        + getConsumerGroup()
        + "', hasRoutingKey='"
        + getHasRoutingKey()
        + "', kafkaClusterId='"
        + getKafkaClusterId()
        + "', partition='"
        + getPartition()
        + "', hash='"
        + getHash()
        + "', aggregationHash='"
        + getAggregationHash()
        + "', size='"
        + size();
  }
}
