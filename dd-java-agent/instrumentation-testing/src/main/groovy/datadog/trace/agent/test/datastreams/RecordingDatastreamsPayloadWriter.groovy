package datadog.trace.agent.test.datastreams

import datadog.trace.api.datastreams.DataStreamsTags
import datadog.trace.core.datastreams.DatastreamsPayloadWriter
import datadog.trace.core.datastreams.StatsBucket
import datadog.trace.core.datastreams.StatsGroup
import groovy.util.logging.Slf4j

import java.util.concurrent.TimeUnit

@Slf4j
class RecordingDatastreamsPayloadWriter implements DatastreamsPayloadWriter {
  @SuppressWarnings('UnusedPrivateField') // bug in codenarc
  private final List<StatsBucket> payloads = []

  @SuppressWarnings('UnusedPrivateField')
  private final List<StatsGroup> groups = []

  @SuppressWarnings('UnusedPrivateField')
  private final Set<DataStreamsTags> backlogs = []

  private final Set<String> serviceNameOverrides = []

  @Override
  synchronized void writePayload(Collection<StatsBucket> data, String serviceNameOverride) {
    log.info("payload written - {}", data)
    serviceNameOverrides.add(serviceNameOverride)
    this.@payloads.addAll(data)
    data.each { this.@groups.addAll(it.groups) }
    for (StatsBucket bucket : data) {
      if (bucket.backlogs != null) {
        for (Map.Entry<DataStreamsTags, Long> backlog : bucket.backlogs) {
          this.@backlogs.add(backlog.getKey())
        }
      }
    }
  }

  synchronized List<String> getServices() {
    Collections.unmodifiableList(new ArrayList<>(this.@serviceNameOverrides))
  }

  synchronized List<StatsBucket> getPayloads() {
    Collections.unmodifiableList(new ArrayList<>(this.@payloads))
  }

  synchronized List<StatsGroup> getGroups() {
    Collections.unmodifiableList(new ArrayList<>(this.@groups))
  }

  synchronized List<DataStreamsTags> getBacklogs() {
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
