package datadog.trace.api.datastreams

import spock.lang.Specification


class DataStreamsTagsTest extends Specification {
  def getTags(int idx) {
    return new DataStreamsTags("bus" + idx, DataStreamsTags.Direction.Outbound, "exchange" + idx, "topic" + idx, "type" + idx, "subscription" + idx,
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
    tg.getDirectionValue() == DataStreamsTags.Direction.Outbound
    tg.toString() == "DataStreamsTags{bus='bus:bus0, direction=direction:out, exchange='exchange:exchange0, topic='topic:topic0, type='type:type0, subscription='subscription:subscription0, datasetName='ds.name:dataset_name0, datasetNamespace='ds.namespace:dataset_namespace0, isManual=manual_checkpoint:true, group='group:group0, consumerGroup='consumer_group:consumer_group0, hasRoutingKey='has_routing_key:true, kafkaClusterId='kafka_cluster_id:kafka_cluster_id0, partition='partition:partition0, hash=8349314675200082083, aggregationHash=1264721246230085006, size=14"
  }

  def 'test service name override and global hash'() {
    setup:
    def one = getTags(0)

    def serviceName = new ThreadLocal<String>()
    serviceName.set("test")
    DataStreamsTags.setServiceNameOverride(serviceName)
    def two = getTags(0)

    DataStreamsTags.setGlobalBaseHash(12)
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
}
