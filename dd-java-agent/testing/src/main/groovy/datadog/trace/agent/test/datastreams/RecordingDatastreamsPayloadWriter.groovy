package datadog.trace.agent.test.datastreams

import datadog.trace.core.datastreams.DatastreamsPayloadWriter
import datadog.trace.core.datastreams.StatsBucket
import datadog.trace.core.datastreams.StatsGroup


import java.util.concurrent.TimeUnit

class RecordingDatastreamsPayloadWriter implements DatastreamsPayloadWriter {
  @SuppressWarnings('UnusedPrivateField') // bug in codenarc
  private final List<StatsBucket> payloads = []

  @SuppressWarnings('UnusedPrivateField')
  private final List<StatsGroup> groups = []

  @SuppressWarnings('UnusedPrivateField')
  private final Set<String> backlogs = []

  @Override
  synchronized void writePayload(Collection<StatsBucket> data) {
    this.@payloads.addAll(data)
    data.each { this.@groups.addAll(it.groups) }
    for (StatsBucket bucket : data) {
      if (bucket.backlogs != null) {
        for (Map.Entry<List<String>, Long> backlog : bucket.backlogs) {
          this.@backlogs.add(backlog.toString())
        }
      }
    }
  }

  synchronized List<StatsBucket> getPayloads() {
    Collections.unmodifiableList(new ArrayList<>(this.@payloads))
  }

  synchronized List<StatsGroup> getGroups() {
    Collections.unmodifiableList(new ArrayList<>(this.@groups))
  }

  synchronized List<String> getBacklogs() {
    Collections.unmodifiableList(new ArrayList<>(this.@backlogs))
  }

  synchronized void clear() {
    this.@payloads.clear()
    this.@groups.clear()
    this.@backlogs.clear()
  }

  void waitForPayloads(int count, long timeout = TimeUnit.SECONDS.toMillis(3)) {
    waitFor(count, timeout, this.@payloads)
  }

  void waitForGroups(int count, long timeout = TimeUnit.SECONDS.toMillis(3)) {
    waitFor(count, timeout, this.@groups)
  }

  void waitForBacklogs(int count, long timeout = TimeUnit.SECONDS.toMillis(3)) {
    waitFor(count, timeout, this.@backlogs)
  }

  private static void waitFor(int count, long timeout, Collection collection) {
    long deadline = System.currentTimeMillis() + timeout
    while (System.currentTimeMillis() < deadline) {
      synchronized (this) {
        if (collection.size() >= count) {
          return
        }
      }
      Thread.sleep(20)
    }

    int finalCollectionCount
    synchronized (this) {
      finalCollectionCount = collection.size()
    }
    assert finalCollectionCount >= count
  }
}
