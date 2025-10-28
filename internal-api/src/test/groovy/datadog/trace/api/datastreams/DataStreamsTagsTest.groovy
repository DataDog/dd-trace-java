package datadog.trace.api.datastreams

import datadog.trace.api.BaseHash
import spock.lang.Specification
import java.nio.ByteBuffer


class DataStreamsTagsTest extends Specification {
  def getTags(int idx) {
    return new DataStreamsTags("bus" + idx, DataStreamsTags.Direction.OUTBOUND, "exchange" + idx, "topic" + idx, "type" + idx, "subscription" + idx,
      "dataset_name" + idx, "dataset_namespace" + idx, true, "group" + idx, "consumer_group" + idx, true,
      "kafka_cluster_id" + idx, "partition" + idx)
  }

  def 'test tags are properly set'() {
    setup:
    def tg = getTags(0)

    expect:
    tg.getBus() == DataStreamsTags.BUS_TAG + ":bus0"
    tg.getDirection() == DataStreamsTags.DIRECTION_TAG + ":out"
    tg.getExchange() == DataStreamsTags.EXCHANGE_TAG + ":exchange0"
    tg.getTopic() == DataStreamsTags.TOPIC_TAG + ":topic0"
    tg.getType() == DataStreamsTags.TYPE_TAG + ":type0"
    tg.getSubscription() == DataStreamsTags.SUBSCRIPTION_TAG + ":subscription0"
    tg.getDatasetName() == DataStreamsTags.DATASET_NAME_TAG + ":dataset_name0"
    tg.getDatasetNamespace() == DataStreamsTags.DATASET_NAMESPACE_TAG + ":dataset_namespace0"
    tg.getIsManual() == DataStreamsTags.MANUAL_TAG + ":true"
    tg.getGroup() == DataStreamsTags.GROUP_TAG + ":group0"
    tg.getConsumerGroup() == DataStreamsTags.CONSUMER_GROUP_TAG + ":consumer_group0"
    tg.getHasRoutingKey() == DataStreamsTags.HAS_ROUTING_KEY_TAG + ":true"
    tg.getKafkaClusterId() == DataStreamsTags.KAFKA_CLUSTER_ID_TAG + ":kafka_cluster_id0"
    tg.getPartition() == DataStreamsTags.PARTITION_TAG + ":partition0"
    tg.getDirectionValue() == DataStreamsTags.Direction.OUTBOUND
    tg.toString() != null
  }

  def 'test has all tags'() {
    setup:
    def tags = new DataStreamsTags("bus", DataStreamsTags.Direction.OUTBOUND,
      "exchange", "topic", "type", "subscription", "dataset_name", "dataset_namespace", true,
      "group", "consumer_group", true, "kafka_cluster_id", "partition")
    expect:
    tags.hasAllTags(
      "bus:bus",
      "direction:out",
      "exchange:exchange",
      "topic:topic",
      "type:type",
      "subscription:subscription",
      "ds.name:dataset_name",
      "ds.namespace:dataset_namespace",
      "manual_checkpoint:true",
      "group:group",
      "consumer_group:consumer_group",
      "has_routing_key:true",
      "kafka_cluster_id:kafka_cluster_id",
      "partition:partition"
      )
    !tags.hasAllTags("garbage")
  }

  def 'test long to bytes'() {
    setup:
    def value = 123444L
    def bts = DataStreamsTags.longToBytes(value)
    ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES)
    buffer.putLong(value)
    def ctrl = buffer.array()
    expect:
    bts == ctrl
  }

  def 'test service name override and global hash'() {
    setup:
    def one = getTags(0)

    def serviceName = new ThreadLocal<String>()
    serviceName.set("test")
    DataStreamsTags.setServiceNameOverride(serviceName)
    def two = getTags(0)

    BaseHash.updateBaseHash(12)
    def three = getTags(0)

    expect:
    one.getHash() != two.getHash()
    one.getAggregationHash() != two.getAggregationHash()
    one.getHash() != three.getHash()
    one.getAggregationHash() != three.getAggregationHash()
    two.getHash() != three.getHash()
    two.getAggregationHash() != three.getAggregationHash()
  }

  def 'test compare'() {
    setup:
    def one = getTags(0)
    def two = getTags(0)
    def three = getTags(1)
    expect:
    one == two
    one != three
    two != three
  }

  def 'test create'() {
    setup:
    def one = DataStreamsTags.create("type", DataStreamsTags.Direction.OUTBOUND)
    def two = DataStreamsTags.create("type", DataStreamsTags.Direction.OUTBOUND, "topic")
    def three = DataStreamsTags.create("type", DataStreamsTags.Direction.OUTBOUND, "topic", "group", "cluster")
    def four = DataStreamsTags.createWithPartition("type", "topic", "partition", "cluster", "group")
    def five = DataStreamsTags.createWithDataset("type", DataStreamsTags.Direction.OUTBOUND, "topic", "dataset", "namespace")
    def six = DataStreamsTags.createWithSubscription("type", DataStreamsTags.Direction.INBOUND, "subscription")
    expect:
    one.hasAllTags("type:type", "direction:out")
    two.hasAllTags("type:type", "direction:out", "topic:topic")
    three.hasAllTags("type:type", "direction:out", "topic:topic", "group:group", "kafka_cluster_id:cluster")
    four.hasAllTags("type:type", "topic:topic", "partition:partition", "kafka_cluster_id:cluster", "consumer_group:group")
    five.hasAllTags("type:type", "direction:out", "topic:topic", "ds.name:dataset", "ds.namespace:namespace")
    six.hasAllTags("type:type", "direction:in", "subscription:subscription")
  }
}
