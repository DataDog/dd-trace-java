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
  }
}
