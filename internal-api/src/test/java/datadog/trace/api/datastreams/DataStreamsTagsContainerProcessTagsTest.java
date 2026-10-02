package datadog.trace.api.datastreams;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.BaseHash;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DataStreamsTagsContainerProcessTagsTest {

  @AfterEach
  void cleanup() {
    BaseHash.recalcBaseHash(null);
    ProcessTags.reset(Config.get());
  }

  private static DataStreamsTags getTags(int idx) {
    return new DataStreamsTags(
        "bus" + idx,
        DataStreamsTags.Direction.OUTBOUND,
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
  void containerTagsHashDoesNotAffectAnyHashTier() {
    // simulate the Agent reporting the pod/container's tags hash at startup
    BaseHash.recalcBaseHash("container-tags-hash-1");
    DataStreamsTags base = getTags(0);

    // a rolling deploy changes the container-tags hash the Agent reports
    BaseHash.recalcBaseHash("container-tags-hash-2");
    DataStreamsTags afterRollingDeploy = getTags(0);

    // DSM2-335: no hash tier is affected - container-tags hash is dropped entirely from DSM
    assertEquals(base.getHash(), afterRollingDeploy.getHash());
    assertEquals(base.getAggregationHash(), afterRollingDeploy.getAggregationHash());
    assertEquals(base, afterRollingDeploy);
  }

  @Test
  void processTagsDoNotAffectAnyHashTier() {
    BaseHash.recalcBaseHash(null);
    DataStreamsTags base = getTags(0);

    // a process tag is added (e.g. cluster.name discovered after startup)
    ProcessTags.addTag("cluster.name", "new-cluster");
    DataStreamsTags withProcessTag = getTags(0);

    // DSM2-335: no hash tier is affected - process tags are dropped entirely from DSM
    assertEquals(base.getHash(), withProcessTag.getHash());
    assertEquals(base.getAggregationHash(), withProcessTag.getAggregationHash());
    assertEquals(base, withProcessTag);
  }
}
