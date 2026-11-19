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

  // base hash and service name override the hashes were seeded with, to detect stale cache entries
  private final long seedBaseHash;
  private final String seedServiceName;
  private long hash;
  private long aggregationHash;
  private long completeHash;
  private int nonNullSize;

  // hash tags
  protected final String bus;
  protected final String direction;
  protected final Direction directionValue;
  protected final String exchange;
  protected final String topic;
  protected final String type;
  protected final String subscription;
  // additional grouping tags
  protected final String datasetName;
  protected final String datasetNamespace;
  protected final String isManual;
  // informational tags
  protected final String group;
  protected final String consumerGroup;
  protected final String hasRoutingKey;
  protected final String kafkaClusterId;
  protected final String partition;

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
    return TagsCache.get(type, direction, topic, group, kafkaClusterId, null, null);
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
    return TagsCache.get(type, null, topic, null, kafkaClusterId, consumerGroup, partition);
  }

  /// For usage in tests *only*
  public Boolean hasAllTags(String[] tags) {
    for (String tag : tags) {
      if (tag.indexOf(':') == -1) {
        return false;
      }
      String key = tag.substring(0, tag.indexOf(':'));
      String value = tag.substring(tag.indexOf(':') + 1);
      switch (key) {
        case BUS_TAG:
          if (!Objects.equals(this.bus, tag)) {
            return false;
          }
          break;
        case DIRECTION_TAG:
          if (!Objects.equals(
              this.directionValue,
              Objects.equals(value, "out") ? Direction.OUTBOUND : Direction.INBOUND)) {
            return false;
          }
          break;
        case EXCHANGE_TAG:
          if (!Objects.equals(this.exchange, tag)) {
            return false;
          }
          break;
        case TOPIC_TAG:
          if (!Objects.equals(this.topic, tag)) {
            return false;
          }
          break;
        case TYPE_TAG:
          if (!Objects.equals(this.type, tag)) {
            return false;
          }
          break;
        case SUBSCRIPTION_TAG:
          if (!Objects.equals(this.subscription, tag)) {
            return false;
          }
          break;
        case DATASET_NAME_TAG:
          if (!Objects.equals(this.datasetName, tag)) {
            return false;
          }
          break;
        case DATASET_NAMESPACE_TAG:
          if (!Objects.equals(this.datasetNamespace, tag)) {
            return false;
          }
          break;
        case MANUAL_TAG:
          if (!Objects.equals(this.isManual, tag)) {
            return false;
          }
          break;
        case GROUP_TAG:
          if (!Objects.equals(this.group, tag)) {
            return false;
          }
          break;
        case CONSUMER_GROUP_TAG:
          if (!Objects.equals(this.consumerGroup, tag)) {
            return false;
          }
          break;
        case HAS_ROUTING_KEY_TAG:
          if (!Objects.equals(this.hasRoutingKey, tag)) {
            return false;
          }
          break;
        case KAFKA_CLUSTER_ID_TAG:
          if (!Objects.equals(this.kafkaClusterId, tag)) {
            return false;
          }
          break;
        case PARTITION_TAG:
          if (!Objects.equals(this.partition, tag)) {
            return false;
          }
          break;
        default:
          return false;
      }
    }

    return true;
  }

  public static DataStreamsTags createWithGroup(
      String type, Direction direction, String topic, String group) {
    return TagsCache.get(type, direction, topic, group, null, null, null);
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
    return TagsCache.get(type, direction, topic, null, clusterId, null, null);
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
    this.bus = bus != null ? BUS_TAG + ":" + bus : null;
    this.directionValue = direction;
    if (direction == Direction.INBOUND) {
      this.direction = DIRECTION_TAG + ":in";
    } else if (direction == Direction.OUTBOUND) {
      this.direction = DIRECTION_TAG + ":out";
    } else {
      this.direction = null;
    }
    this.exchange = exchange != null ? EXCHANGE_TAG + ":" + exchange : null;
    this.topic = topic != null ? TOPIC_TAG + ":" + topic : null;
    this.type = type != null ? TYPE_TAG + ":" + type : null;
    this.subscription = subscription != null ? SUBSCRIPTION_TAG + ":" + subscription : null;
    this.datasetName = datasetName != null ? DATASET_NAME_TAG + ":" + datasetName : null;
    this.datasetNamespace =
        datasetNamespace != null ? DATASET_NAMESPACE_TAG + ":" + datasetNamespace : null;
    this.isManual = isManual != null ? MANUAL_TAG + ":" + isManual : null;
    this.group = group != null ? GROUP_TAG + ":" + group : null;
    this.consumerGroup = consumerGroup != null ? CONSUMER_GROUP_TAG + ":" + consumerGroup : null;
    this.hasRoutingKey = hasRoutingKey != null ? HAS_ROUTING_KEY_TAG + ":" + hasRoutingKey : null;
    this.kafkaClusterId =
        kafkaClusterId != null ? KAFKA_CLUSTER_ID_TAG + ":" + kafkaClusterId : null;
    this.partition = partition != null ? PARTITION_TAG + ":" + partition : null;

    this.seedBaseHash = BaseHash.getBaseHash();
    this.seedServiceName = currentServiceNameOverride();
    this.hash = this.seedBaseHash;
    if (this.seedServiceName != null) {
      this.hash = FNV64Hash.continueHash(this.hash, this.seedServiceName, FNV64Hash.Version.v1);
    }

    // hashable tags are 0-6: bus, direction, exchange, topic, type, subscription, kafkaClusterId
    for (int i = 0; i < 7; i++) {
      String tag = this.tagByIndex(i);
      if (tag != null) {
        this.nonNullSize++;
        this.hash = FNV64Hash.continueHash(this.hash, tag, FNV64Hash.Version.v1);
      }
    }

    // aggregation tags are 7-11: datasetName, datasetNamespace, isManual, group, consumerGroup
    this.aggregationHash = this.hash;
    for (int i = 7; i < 12; i++) {
      String tag = this.tagByIndex(i);
      if (tag != null) {
        this.nonNullSize++;
        this.aggregationHash =
            FNV64Hash.continueHash(this.aggregationHash, tag, FNV64Hash.Version.v1);
      }
    }

    // values are 12-13: partition, hasRoutingKey
    this.completeHash = aggregationHash;
    for (int i = 12; i < this.size(); i++) {
      String tag = this.tagByIndex(i);
      if (tag != null) {
        this.nonNullSize++;
        this.completeHash = FNV64Hash.continueHash(this.completeHash, tag, FNV64Hash.Version.v1);
      }
    }
  }

  private static String currentServiceNameOverride() {
    ThreadLocal<String> override = DataStreamsTags.serviceNameOverride;
    return override != null ? override.get() : null;
  }

  public int size() {
    // make sure it's in sync with tagByIndex logic
    return 14;
  }

  // WARNING: DO NOT REORDER! Indices 0-6 are hash tags, 7-11 are aggregation tags, 12-13 are
  // values. Reordering breaks hashing!
  public String tagByIndex(int index) {
    switch (index) {
      case 0:
        return this.bus;
      case 1:
        return this.direction;
      case 2:
        return this.exchange;
      case 3:
        return this.topic;
      case 4:
        return this.type;
      case 5:
        return this.subscription;
      case 6:
        return this.kafkaClusterId;
      case 7:
        return this.datasetName;
      case 8:
        return this.datasetNamespace;
      case 9:
        return this.isManual;
      case 10:
        return this.group;
      case 11:
        return this.consumerGroup;
      case 12:
        return this.partition;
      case 13:
        return this.hasRoutingKey;
      default:
        return null;
    }
  }

  public String getDirection() {
    return this.direction;
  }

  public String getTopic() {
    return this.topic;
  }

  public String getType() {
    return this.type;
  }

  public String getIsManual() {
    return this.isManual;
  }

  public String getBus() {
    return this.bus;
  }

  public String getExchange() {
    return this.exchange;
  }

  public Direction getDirectionValue() {
    return this.directionValue;
  }

  public String getSubscription() {
    return this.subscription;
  }

  public String getDatasetName() {
    return this.datasetName;
  }

  public String getDatasetNamespace() {
    return this.datasetNamespace;
  }

  public String getGroup() {
    return this.group;
  }

  public String getConsumerGroup() {
    return this.consumerGroup;
  }

  public String getPartition() {
    return this.partition;
  }

  public String getKafkaClusterId() {
    return this.kafkaClusterId;
  }

  public String getHasRoutingKey() {
    return this.hasRoutingKey;
  }

  public int nonNullSize() {
    return this.nonNullSize;
  }

  public long getHash() {
    return hash;
  }

  public long getAggregationHash() {
    return aggregationHash;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    DataStreamsTags that = (DataStreamsTags) o;
    return this.completeHash == that.completeHash;
  }

  @Override
  public int hashCode() {
    return Long.hashCode(this.completeHash);
  }

  @Override
  public String toString() {
    return "DataStreamsTags{"
        + "bus='"
        + this.bus
        + "', direction='"
        + this.direction
        + "', exchange='"
        + this.exchange
        + "', topic='"
        + this.topic
        + "', type='"
        + this.type
        + "', subscription='"
        + this.subscription
        + "', datasetName='"
        + this.datasetName
        + "', datasetNamespace='"
        + this.datasetNamespace
        + "', isManual="
        + this.isManual
        + "', group='"
        + this.group
        + "', consumerGroup='"
        + this.consumerGroup
        + "', hasRoutingKey='"
        + this.hasRoutingKey
        + "', kafkaClusterId='"
        + this.kafkaClusterId
        + "', partition='"
        + this.partition
        + "', hash='"
        + hash
        + "', aggregationHash='"
        + aggregationHash
        + "', size='"
        + size();
  }

  /**
   * Fixed-size cache of the tags built per record by the Kafka instrumentations, whose inputs are
   * constant per topic, group and cluster. It avoids the string concatenation and hashing of the
   * constructor. An entry whose seed (base hash or service name override) no longer matches the
   * current one is rebuilt, so the cached tags are always identical to freshly built ones.
   *
   * <p>The cache is thread safe: entries are immutable, and any race is benign since a full entry
   * is written or read.
   */
  private static final class TagsCache {
    private static final int CAPACITY = 1024; // power of two
    private static final Entry[] ENTRIES = new Entry[CAPACITY];

    static DataStreamsTags get(
        String type,
        Direction direction,
        String topic,
        String group,
        String kafkaClusterId,
        String consumerGroup,
        String partition) {
      int h = direction == null ? 0 : direction.ordinal() + 1;
      h = 31 * h + Objects.hashCode(type);
      h = 31 * h + Objects.hashCode(topic);
      h = 31 * h + Objects.hashCode(group);
      h = 31 * h + Objects.hashCode(kafkaClusterId);
      h = 31 * h + Objects.hashCode(consumerGroup);
      h = 31 * h + Objects.hashCode(partition);
      int index = (h ^ (h >>> 16)) & (CAPACITY - 1);

      Entry entry = ENTRIES[index];
      if (entry != null
          && entry.matches(type, direction, topic, group, kafkaClusterId, consumerGroup, partition)
          && entry.tags.seedBaseHash == BaseHash.getBaseHash()
          && Objects.equals(entry.tags.seedServiceName, currentServiceNameOverride())) {
        return entry.tags;
      }
      DataStreamsTags tags =
          new DataStreamsTags(
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
              consumerGroup,
              null,
              kafkaClusterId,
              partition);
      ENTRIES[index] =
          new Entry(type, direction, topic, group, kafkaClusterId, consumerGroup, partition, tags);
      return tags;
    }

    private static final class Entry {
      private final String type;
      private final Direction direction;
      private final String topic;
      private final String group;
      private final String kafkaClusterId;
      private final String consumerGroup;
      private final String partition;
      final DataStreamsTags tags;

      Entry(
          String type,
          Direction direction,
          String topic,
          String group,
          String kafkaClusterId,
          String consumerGroup,
          String partition,
          DataStreamsTags tags) {
        this.type = type;
        this.direction = direction;
        this.topic = topic;
        this.group = group;
        this.kafkaClusterId = kafkaClusterId;
        this.consumerGroup = consumerGroup;
        this.partition = partition;
        this.tags = tags;
      }

      boolean matches(
          String type,
          Direction direction,
          String topic,
          String group,
          String kafkaClusterId,
          String consumerGroup,
          String partition) {
        return this.direction == direction
            && Objects.equals(this.type, type)
            && Objects.equals(this.topic, topic)
            && Objects.equals(this.group, group)
            && Objects.equals(this.kafkaClusterId, kafkaClusterId)
            && Objects.equals(this.consumerGroup, consumerGroup)
            && Objects.equals(this.partition, partition);
      }
    }
  }
}
