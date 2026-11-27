package datadog.trace.api.datastreams;

import static datadog.trace.api.BaseHash.getBaseHash;
import static datadog.trace.api.BaseHash.updateBaseHash;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.setServiceNameOverride;
import static datadog.trace.util.FNV64Hash.Version.v1;
import static datadog.trace.util.FNV64Hash.continueHash;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.datastreams.DataStreamsTags.Direction;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DataStreamsTagsTest {
  private long originalBaseHash;

  @BeforeEach
  void captureBaseHash() {
    originalBaseHash = getBaseHash();
  }

  /** The override and base hash are global, so a test changing them mustn't leak into others. */
  @AfterEach
  void restoreGlobals() {
    setServiceNameOverride(null);
    updateBaseHash(originalBaseHash);
  }

  private static DataStreamsTags getTags(int idx) {
    return new DataStreamsTags(
        "bus" + idx,
        OUTBOUND,
        "exchange" + idx,
        "topic" + idx,
        "type" + idx,
        "subscription" + idx,
        "dataset_name" + idx,
        "dataset_namespace" + idx,
        true,
        "group" + idx,
        "consumer_group" + idx,
        true,
        "kafka_cluster_id" + idx,
        "partition" + idx);
  }

  @Test
  void testTagsAreProperlySet() {
    DataStreamsTags tags = getTags(0);

    assertEquals(DataStreamsTags.BUS_TAG + ":bus0", tags.getBus());
    assertEquals(DataStreamsTags.DIRECTION_TAG + ":out", tags.getDirection());
    assertEquals(DataStreamsTags.EXCHANGE_TAG + ":exchange0", tags.getExchange());
    assertEquals(DataStreamsTags.TOPIC_TAG + ":topic0", tags.getTopic());
    assertEquals(DataStreamsTags.TYPE_TAG + ":type0", tags.getType());
    assertEquals(DataStreamsTags.SUBSCRIPTION_TAG + ":subscription0", tags.getSubscription());
    assertEquals(DataStreamsTags.DATASET_NAME_TAG + ":dataset_name0", tags.getDatasetName());
    assertEquals(
        DataStreamsTags.DATASET_NAMESPACE_TAG + ":dataset_namespace0", tags.getDatasetNamespace());
    assertEquals(DataStreamsTags.MANUAL_TAG + ":true", tags.getIsManual());
    assertEquals(DataStreamsTags.GROUP_TAG + ":group0", tags.getGroup());
    assertEquals(DataStreamsTags.CONSUMER_GROUP_TAG + ":consumer_group0", tags.getConsumerGroup());
    assertEquals(DataStreamsTags.HAS_ROUTING_KEY_TAG + ":true", tags.getHasRoutingKey());
    assertEquals(
        DataStreamsTags.KAFKA_CLUSTER_ID_TAG + ":kafka_cluster_id0", tags.getKafkaClusterId());
    assertEquals(DataStreamsTags.PARTITION_TAG + ":partition0", tags.getPartition());
    assertEquals(OUTBOUND, tags.getDirectionValue());
    assertNotNull(tags.toString());
  }

  @Test
  void testHasAllTags() {
    DataStreamsTags tags =
        new DataStreamsTags(
            "bus",
            OUTBOUND,
            "exchange",
            "topic",
            "type",
            "subscription",
            "dataset_name",
            "dataset_namespace",
            true,
            "group",
            "consumer_group",
            true,
            "kafka_cluster_id",
            "partition");

    assertTrue(
        tags.hasAllTags(
            new String[] {
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
            }));
    assertFalse(tags.hasAllTags(new String[] {"garbage"}));
  }

  @Test
  void hasAllTagsRejectsTagsItDoesNotHave() {
    DataStreamsTags tags = DataStreamsTags.create("kafka", INBOUND, "orders", "group", "cluster");

    assertTrue(tags.hasAllTags(new String[] {"topic:orders", "direction:in", "group:group"}));
    assertFalse(tags.hasAllTags(new String[] {"topic:payments"}), "different value");
    assertFalse(tags.hasAllTags(new String[] {"exchange:orders"}), "absent tag");
    assertFalse(tags.hasAllTags(new String[] {"unknown:orders"}), "unknown name");
    assertFalse(tags.hasAllTags(new String[] {"direction:out"}), "different direction");
    assertFalse(tags.hasAllTags(new String[] {"direction:garbage"}), "unknown direction");
    assertFalse(tags.hasAllTags(new String[] {"topic:orders", "group:other"}), "one mismatch");
  }

  @Test
  void testLongToBytes() {
    long value = 123444L;
    byte[] bytes = DataStreamsTags.longToBytes(value);
    ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
    buffer.putLong(value);
    byte[] expected = buffer.array();

    assertArrayEquals(expected, bytes);
  }

  @Test
  void testServiceNameOverrideAndGlobalHash() {
    DataStreamsTags one = getTags(0);

    ThreadLocal<String> serviceName = new ThreadLocal<>();
    serviceName.set("test");
    setServiceNameOverride(serviceName);
    DataStreamsTags two = getTags(0);

    updateBaseHash(12);
    DataStreamsTags three = getTags(0);

    assertNotEquals(one.getHash(), two.getHash());
    assertNotEquals(one.getAggregationHash(), two.getAggregationHash());
    assertNotEquals(one.getHash(), three.getHash());
    assertNotEquals(one.getAggregationHash(), three.getAggregationHash());
    assertNotEquals(two.getHash(), three.getHash());
    assertNotEquals(two.getAggregationHash(), three.getAggregationHash());
  }

  @Test
  void testCompare() {
    DataStreamsTags one = getTags(0);
    DataStreamsTags two = getTags(0);
    DataStreamsTags three = getTags(1);

    assertEquals(one, two);
    assertNotEquals(one, three);
    assertNotEquals(two, three);
  }

  @Test
  void testCreate() {
    DataStreamsTags one = DataStreamsTags.create("type", OUTBOUND);
    DataStreamsTags two = DataStreamsTags.create("type", OUTBOUND, "topic");
    DataStreamsTags three = DataStreamsTags.create("type", OUTBOUND, "topic", "group", "cluster");
    DataStreamsTags four =
        DataStreamsTags.createWithPartition("type", "topic", "partition", "cluster", "group");
    DataStreamsTags five =
        DataStreamsTags.createWithDataset("type", OUTBOUND, "topic", "dataset", "namespace");
    DataStreamsTags six =
        DataStreamsTags.createWithSubscription("type", Direction.INBOUND, "subscription");

    assertTrue(one.hasAllTags(new String[] {"type:type", "direction:out"}));
    assertTrue(two.hasAllTags(new String[] {"type:type", "direction:out", "topic:topic"}));
    assertTrue(
        three.hasAllTags(
            new String[] {
              "type:type", "direction:out", "topic:topic", "group:group", "kafka_cluster_id:cluster"
            }));
    assertTrue(
        four.hasAllTags(
            new String[] {
              "type:type",
              "topic:topic",
              "partition:partition",
              "kafka_cluster_id:cluster",
              "consumer_group:group"
            }));
    assertTrue(
        five.hasAllTags(
            new String[] {
              "type:type",
              "direction:out",
              "topic:topic",
              "ds.name:dataset",
              "ds.namespace:namespace"
            }));
    assertTrue(
        six.hasAllTags(new String[] {"type:type", "direction:in", "subscription:subscription"}));
  }

  @Test
  void testTagByIndexReturnsTagsInCorrectOrder() {
    DataStreamsTags tags = getTags(0);

    // Hash tags (0-6): bus, direction, exchange, topic, type, subscription, kafkaClusterId
    assertEquals("bus:bus0", tags.tagByIndex(0));
    assertEquals("direction:out", tags.tagByIndex(1));
    assertEquals("exchange:exchange0", tags.tagByIndex(2));
    assertEquals("topic:topic0", tags.tagByIndex(3));
    assertEquals("type:type0", tags.tagByIndex(4));
    assertEquals("subscription:subscription0", tags.tagByIndex(5));
    assertEquals("kafka_cluster_id:kafka_cluster_id0", tags.tagByIndex(6));

    // Aggregation tags (7-11): datasetName, datasetNamespace, isManual, group, consumerGroup
    assertEquals("ds.name:dataset_name0", tags.tagByIndex(7));
    assertEquals("ds.namespace:dataset_namespace0", tags.tagByIndex(8));
    assertEquals("manual_checkpoint:true", tags.tagByIndex(9));
    assertEquals("group:group0", tags.tagByIndex(10));
    assertEquals("consumer_group:consumer_group0", tags.tagByIndex(11));

    // Values (12-13): partition, hasRoutingKey
    assertEquals("partition:partition0", tags.tagByIndex(12));
    assertEquals("has_routing_key:true", tags.tagByIndex(13));

    // Out of bounds returns null
    assertNull(tags.tagByIndex(14));
    assertNull(tags.tagByIndex(-1));
  }

  @Test
  void testOnlyHashTagsAffectPrimaryHash() {
    // Create base tags with all hash tags (0-6) set
    DataStreamsTags base =
        new DataStreamsTags(
            "bus",
            OUTBOUND,
            "exchange",
            "topic",
            "type",
            "subscription",
            null,
            null,
            null,
            null,
            null,
            null,
            "cluster",
            null);

    // Change only aggregation tag (datasetName)
    DataStreamsTags withDataset =
        new DataStreamsTags(
            "bus",
            OUTBOUND,
            "exchange",
            "topic",
            "type",
            "subscription",
            "dataset",
            null,
            null,
            null,
            null,
            null,
            "cluster",
            null);

    // Primary hash should be the same (aggregation tag doesn't affect it)
    assertEquals(base.getHash(), withDataset.getHash());

    // Change only a value tag (partition)
    DataStreamsTags withPartition =
        new DataStreamsTags(
            "bus",
            OUTBOUND,
            "exchange",
            "topic",
            "type",
            "subscription",
            null,
            null,
            null,
            null,
            null,
            null,
            "cluster",
            "partition");

    // Primary hash should still be the same (value tag doesn't affect it)
    assertEquals(base.getHash(), withPartition.getHash());

    // Change a hash tag (topic)
    DataStreamsTags withDifferentTopic =
        new DataStreamsTags(
            "bus",
            OUTBOUND,
            "exchange",
            "topic2",
            "type",
            "subscription",
            null,
            null,
            null,
            null,
            null,
            null,
            "cluster",
            null);

    // Primary hash should be different
    assertNotEquals(base.getHash(), withDifferentTopic.getHash());
  }

  @Test
  void testAggregationTagsAffectAggregationHashButNotPrimaryHash() {
    // Create base tags
    DataStreamsTags base =
        new DataStreamsTags(
            "bus",
            OUTBOUND,
            "exchange",
            "topic",
            "type",
            "subscription",
            null,
            null,
            null,
            null,
            null,
            null,
            "cluster",
            null);

    // Add aggregation tag (datasetName)
    DataStreamsTags withDataset =
        new DataStreamsTags(
            "bus",
            OUTBOUND,
            "exchange",
            "topic",
            "type",
            "subscription",
            "dataset",
            null,
            null,
            null,
            null,
            null,
            "cluster",
            null);

    // Primary hash is same, but aggregation hash is different
    assertEquals(base.getHash(), withDataset.getHash());
    assertNotEquals(base.getAggregationHash(), withDataset.getAggregationHash());

    // Add different aggregation tag (group)
    DataStreamsTags withGroup =
        new DataStreamsTags(
            "bus",
            OUTBOUND,
            "exchange",
            "topic",
            "type",
            "subscription",
            null,
            null,
            null,
            "group",
            null,
            null,
            "cluster",
            null);

    // Primary hash is same, but aggregation hash is different
    assertEquals(base.getHash(), withGroup.getHash());
    assertNotEquals(base.getAggregationHash(), withGroup.getAggregationHash());
  }

  @Test
  void testValuesAffectOnlyCompleteHash() {
    // Create base tags
    DataStreamsTags base =
        new DataStreamsTags(
            "bus",
            OUTBOUND,
            "exchange",
            "topic",
            "type",
            "subscription",
            null,
            null,
            null,
            null,
            null,
            null,
            "cluster",
            null);

    // Add value tag (partition)
    DataStreamsTags withPartition =
        new DataStreamsTags(
            "bus",
            OUTBOUND,
            "exchange",
            "topic",
            "type",
            "subscription",
            null,
            null,
            null,
            null,
            null,
            null,
            "cluster",
            "partition");

    // Primary and aggregation hashes are same, complete hash is different (via equals)
    assertEquals(base.getHash(), withPartition.getHash());
    assertEquals(base.getAggregationHash(), withPartition.getAggregationHash());
    assertNotEquals(base, withPartition); // equals uses completeHash

    // Add different value tag (hasRoutingKey)
    DataStreamsTags withRoutingKey =
        new DataStreamsTags(
            "bus",
            OUTBOUND,
            "exchange",
            "topic",
            "type",
            "subscription",
            null,
            null,
            null,
            null,
            null,
            true,
            "cluster",
            null);

    // Primary and aggregation hashes are same, but objects are different
    assertEquals(base.getHash(), withRoutingKey.getHash());
    assertEquals(base.getAggregationHash(), withRoutingKey.getAggregationHash());
    assertNotEquals(base, withRoutingKey);
  }

  @Test
  void testAllThreeHashLevelsAreDifferentWhenAppropriateTagsChange() {
    DataStreamsTags base =
        new DataStreamsTags(
            "bus", OUTBOUND, null, "topic", "type", null, null, null, null, null, null, null, null,
            null);

    // Add hash tag -> all hashes change
    DataStreamsTags withExchange =
        new DataStreamsTags(
            "bus",
            OUTBOUND,
            "exchange",
            "topic",
            "type",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertNotEquals(base.getHash(), withExchange.getHash());
    assertNotEquals(base.getAggregationHash(), withExchange.getAggregationHash());
    assertNotEquals(base, withExchange);

    // Add aggregation tag -> only aggregation and complete hashes change
    DataStreamsTags withDataset =
        new DataStreamsTags(
            "bus", OUTBOUND, null, "topic", "type", null, "dataset", null, null, null, null, null,
            null, null);

    assertEquals(base.getHash(), withDataset.getHash()); // primary hash unchanged
    assertNotEquals(base.getAggregationHash(), withDataset.getAggregationHash());
    assertNotEquals(base, withDataset);

    // Add value tag -> only complete hash changes
    DataStreamsTags withPartition =
        new DataStreamsTags(
            "bus",
            OUTBOUND,
            null,
            "topic",
            "type",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "partition");

    assertEquals(base.getHash(), withPartition.getHash()); // primary hash unchanged
    assertEquals(
        base.getAggregationHash(),
        withPartition.getAggregationHash()); // aggregation hash unchanged
    assertNotEquals(base, withPartition); // but complete hash changed
  }

  @Test
  void hashesMatchHashingTheEncodedTags() {
    DataStreamsTags[] tagsList = {
      getTags(0),
      DataStreamsTags.create(null, null),
      DataStreamsTags.create("kafka", INBOUND, "orders", "group", "cluster"),
      DataStreamsTags.createWithExchange("rabbitmq", OUTBOUND, "exchange", false),
      DataStreamsTags.createWithDataset("s3", Direction.UNKNOWN, null, "caf\u00e9", "\u6ce8\ud83d"),
      new DataStreamsTags(
          "bus\ud83d\ude80",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          "consumer",
          null,
          null,
          "3")
    };
    for (DataStreamsTags tags : tagsList) {
      assertHashesMatchEncodedTags(tags, getBaseHash(), null);
    }
  }

  @Test
  void hashesUseTheBaseHashAndServiceNameOverrideOfConstruction() {
    long baseHash = getBaseHash();
    ThreadLocal<String> serviceName = new ThreadLocal<>();
    serviceName.set("service-at-construction");
    setServiceNameOverride(serviceName);
    DataStreamsTags tags = getTags(1);

    // Changed before the hashes are first read, which mustn't change them
    serviceName.set("service-later");
    updateBaseHash(baseHash + 1);

    assertHashesMatchEncodedTags(tags, baseHash, "service-at-construction");
  }

  /** Checks the hashes against FNV-64 over the UTF-8 encoded "name:value" tags. */
  private static void assertHashesMatchEncodedTags(
      DataStreamsTags tags, long baseHash, String serviceName) {
    long hash = baseHash;
    if (serviceName != null) {
      hash = hashEncoded(hash, serviceName);
    }
    int nonNullSize = 0;
    for (int i = 0; i < DataStreamsTags.AGGREGATION_TAGS_START; i++) {
      String tag = tags.tagByIndex(i);
      if (tag != null) {
        nonNullSize++;
        hash = hashEncoded(hash, tag);
      }
    }
    long aggregationHash = hash;
    for (int i = DataStreamsTags.AGGREGATION_TAGS_START;
        i < DataStreamsTags.VALUE_TAGS_START;
        i++) {
      String tag = tags.tagByIndex(i);
      if (tag != null) {
        nonNullSize++;
        aggregationHash = hashEncoded(aggregationHash, tag);
      }
    }
    long completeHash = aggregationHash;
    for (int i = DataStreamsTags.VALUE_TAGS_START; i < tags.size(); i++) {
      String tag = tags.tagByIndex(i);
      if (tag != null) {
        nonNullSize++;
        completeHash = hashEncoded(completeHash, tag);
      }
    }
    assertEquals(hash, tags.getHash(), tags::toString);
    assertEquals(aggregationHash, tags.getAggregationHash(), tags::toString);
    // The complete hash isn't exposed, but equals and hashCode are defined by it
    assertEquals(Long.hashCode(completeHash), tags.hashCode(), tags::toString);
    assertEquals(nonNullSize, tags.nonNullSize(), tags::toString);
  }

  private static long hashEncoded(long hash, String value) {
    return continueHash(hash, value.getBytes(UTF_8), v1);
  }
}
