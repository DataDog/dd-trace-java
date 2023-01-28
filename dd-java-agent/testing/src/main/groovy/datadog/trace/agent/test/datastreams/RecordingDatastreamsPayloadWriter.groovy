package datadog.trace.agent.test.datastreams

import datadog.trace.core.datastreams.DatastreamsPayloadWriter
import datadog.trace.core.datastreams.StatsBucket
import datadog.trace.core.datastreams.StatsGroup
import datadog.trace.core.datastreams.TopicPartition
import datadog.trace.core.datastreams.TopicPartitionGroup

import java.util.concurrent.TimeUnit

class RecordingDatastreamsPayloadWriter implements DatastreamsPayloadWriter {
  List<Collection<StatsBucket>> payloads = []
  List<StatsGroup> groups = []
  List<Map.Entry<TopicPartition, Long>> producerOffsets = []
  List<Map.Entry<TopicPartitionGroup, Long>> commitOffsets = []

  @Override
  void writePayload(Collection<StatsBucket> data) {
    payloads.add(data)
    data.each { groups.addAll(it.groups) }
    data.each {producerOffsets.addAll(it.latestKafkaProduceOffsets)}
    // we filter out commits for offsets 0
    data.each {commitOffsets.addAll(it.latestKafkaCommitOffsets.findAll {it.value != 0})}
  }

  void clear() {
    payloads.clear()
    groups.clear()
    producerOffsets.clear()
    commitOffsets.clear()
  }

  void waitForPayloads(int count, long timeout = TimeUnit.SECONDS.toMillis(3)) {
    waitFor(count, timeout, payloads)
  }

  void waitForGroups(int count, long timeout = TimeUnit.SECONDS.toMillis(3)) {
    waitFor(count, timeout, groups)
  }

  void waitForKafkaOffsets(int commitCount, int produceCount, long timeout = TimeUnit.SECONDS.toMillis(3)) {
    waitFor(commitCount, timeout, commitOffsets)
    waitFor(produceCount, timeout, producerOffsets)
  }

  private static void waitFor(int count, long timeout, Collection collection) {
    long deadline = System.currentTimeMillis() + timeout
    while (collection.size() < count && System.currentTimeMillis() < deadline) {
      Thread.sleep(20)
    }

    assert collection.size() >= count
  }
}
