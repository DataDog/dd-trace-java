package datadog.trace.agent.test.datastreams

import datadog.trace.api.Platform
import datadog.trace.core.datastreams.DatastreamsPayloadWriter
import datadog.trace.core.datastreams.StatsBucket
import datadog.trace.core.datastreams.StatsGroup

import java.util.concurrent.TimeUnit

class RecordingDatastreamsPayloadWriter implements DatastreamsPayloadWriter {
  List<Collection<StatsBucket>> payloads = []
  List<StatsBucket.BucketType> payloadBucketTypes = []
  List<StatsGroup> groups = []
  List<StatsBucket.BucketType> groupBucketTypes = []

  @Override
  void writePayload(Collection<StatsBucket> data, StatsBucket.BucketType bucketType) {
    payloads.add(data)
    payloadBucketTypes.add(bucketType)
    data.each {
      groups.addAll(it.groups)
      groupBucketTypes.add([bucketType] * it.groups.size())
    }
  }

  void clear() {
    payloads.clear()
    groups.clear()
    payloadBucketTypes.clear()
    groupBucketTypes.clear()
  }

  void waitForPayloads(int count, long timeout = TimeUnit.SECONDS.toMillis(1)) {
    waitFor(count, timeout, payloads)
  }

  void waitForGroups(int count, long timeout = TimeUnit.SECONDS.toMillis(1)) {
    waitFor(count, timeout, groups)
  }

  private static void waitFor(int count, long timeout, Collection collection) {
    if (Platform.isJavaVersionAtLeast(8)) {
      long deadline = System.currentTimeMillis() + timeout
      while (collection.size() < count && System.currentTimeMillis() < deadline) {
        Thread.sleep(20)
      }

      assert collection.size() >= count
    }
  }
}
