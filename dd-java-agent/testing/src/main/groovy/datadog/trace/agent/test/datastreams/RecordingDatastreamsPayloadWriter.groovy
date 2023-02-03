package datadog.trace.agent.test.datastreams

import datadog.trace.core.datastreams.DatastreamsPayloadWriter
import datadog.trace.core.datastreams.StatsBucket
import datadog.trace.core.datastreams.StatsGroup


import java.util.concurrent.TimeUnit

class RecordingDatastreamsPayloadWriter implements DatastreamsPayloadWriter {
  List<StatsBucket> payloads = []
  List<StatsGroup> groups = []
  Set<String> backlogs = []

  @Override
  void writePayload(Collection<StatsBucket> data) {
    payloads.addAll(data)
    data.each { groups.addAll(it.groups) }
    for (StatsBucket bucket : data) {
      if (bucket.backlogs != null) {
        for (Map.Entry<List<String>, Long> backlog : bucket.backlogs) {
          backlogs.add(backlog.toString())
        }
      }
    }
  }

  void clear() {
    payloads.clear()
    groups.clear()
    backlogs.clear()
  }

  void waitForPayloads(int count, long timeout = TimeUnit.SECONDS.toMillis(3)) {
    waitFor(count, timeout, payloads)
  }

  void waitForGroups(int count, long timeout = TimeUnit.SECONDS.toMillis(3)) {
    waitFor(count, timeout, groups)
  }

  void waitForBacklogs(int count, long timeout = TimeUnit.SECONDS.toMillis(3)) {
    waitFor(count, timeout, backlogs)
  }

  private static void waitFor(int count, long timeout, Collection collection) {
    long deadline = System.currentTimeMillis() + timeout
    while (collection.size() < count && System.currentTimeMillis() < deadline) {
      Thread.sleep(20)
    }

    assert collection.size() >= count
  }
}
