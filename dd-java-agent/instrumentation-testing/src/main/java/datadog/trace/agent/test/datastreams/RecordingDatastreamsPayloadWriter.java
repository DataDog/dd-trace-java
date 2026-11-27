package datadog.trace.agent.test.datastreams;

import static java.util.Collections.unmodifiableList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.core.datastreams.DatastreamsPayloadWriter;
import datadog.trace.core.datastreams.StatsBucket;
import datadog.trace.core.datastreams.StatsGroup;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RecordingDatastreamsPayloadWriter implements DatastreamsPayloadWriter {
  private static final Logger log =
      LoggerFactory.getLogger(RecordingDatastreamsPayloadWriter.class);

  private static final long DEFAULT_TIMEOUT_MILLIS = SECONDS.toMillis(3);
  private static final long GROUP_POLL_INTERVAL_MILLIS = 100;
  private static final long COUNT_POLL_INTERVAL_MILLIS = 20;

  private final List<StatsBucket> payloads = new ArrayList<>();
  private final List<StatsGroup> groups = new ArrayList<>();
  private final Set<DataStreamsTags> backlogs = new LinkedHashSet<>();
  private final List<StatsBucket.SchemaKey> schemaRegistryUsages = new ArrayList<>();
  private final Set<String> serviceNameOverrides = new LinkedHashSet<>();

  @Override
  public synchronized void writePayload(Collection<StatsBucket> data, String serviceNameOverride) {
    log.info("payload written - {}", data);
    serviceNameOverrides.add(serviceNameOverride);
    payloads.addAll(data);
    for (StatsBucket bucket : data) {
      groups.addAll(bucket.getGroups());
      for (Map.Entry<DataStreamsTags, Long> backlog : bucket.getBacklogs()) {
        backlogs.add(backlog.getKey());
      }
      for (Map.Entry<StatsBucket.SchemaKey, Long> usage : bucket.getSchemaRegistryUsages()) {
        schemaRegistryUsages.add(usage.getKey());
      }
    }
  }

  public synchronized List<String> getServices() {
    return unmodifiableList(new ArrayList<>(serviceNameOverrides));
  }

  public synchronized List<StatsBucket> getPayloads() {
    return unmodifiableList(new ArrayList<>(payloads));
  }

  public synchronized List<StatsGroup> getGroups() {
    return unmodifiableList(new ArrayList<>(groups));
  }

  public synchronized List<DataStreamsTags> getBacklogs() {
    return unmodifiableList(new ArrayList<>(backlogs));
  }

  public synchronized List<StatsBucket.SchemaKey> getSchemaRegistryUsages() {
    return unmodifiableList(new ArrayList<>(schemaRegistryUsages));
  }

  public synchronized void clear() {
    payloads.clear();
    groups.clear();
    backlogs.clear();
    schemaRegistryUsages.clear();
  }

  public void waitForPayloads(int count) throws InterruptedException {
    waitForPayloads(count, DEFAULT_TIMEOUT_MILLIS);
  }

  public void waitForPayloads(int count, long timeout) throws InterruptedException {
    waitFor(count, timeout, payloads, "payloads");
  }

  public void waitForGroups(int count) throws InterruptedException {
    waitForGroups(count, DEFAULT_TIMEOUT_MILLIS);
  }

  public void waitForGroups(int count, long timeout) throws InterruptedException {
    waitFor(count, timeout, groups, "groups");
  }

  public void waitForBacklogs(int count) throws InterruptedException {
    waitForBacklogs(count, DEFAULT_TIMEOUT_MILLIS);
  }

  public void waitForBacklogs(int count, long timeout) throws InterruptedException {
    waitFor(count, timeout, backlogs, "backlogs");
  }

  public void waitForSchemaRegistryUsages(int count) throws InterruptedException {
    waitForSchemaRegistryUsages(count, DEFAULT_TIMEOUT_MILLIS);
  }

  public void waitForSchemaRegistryUsages(int count, long timeout) throws InterruptedException {
    waitFor(count, timeout, schemaRegistryUsages, "schema registry usages");
  }

  public StatsGroup waitForGroup(Predicate<StatsGroup> predicate) throws InterruptedException {
    return waitForGroup(predicate, DEFAULT_TIMEOUT_MILLIS);
  }

  public StatsGroup waitForGroup(Predicate<StatsGroup> predicate, long timeout)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeout;
    while (System.currentTimeMillis() < deadline) {
      synchronized (this) {
        for (StatsGroup group : groups) {
          if (predicate.test(group)) {
            return group;
          }
        }
      }
      Thread.sleep(GROUP_POLL_INTERVAL_MILLIS);
    }

    return fail("Expected a matching stats group within " + timeout + "ms");
  }

  private void waitFor(int count, long timeout, Collection<?> collection, String description)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeout;
    while (System.currentTimeMillis() < deadline) {
      synchronized (this) {
        if (collection.size() >= count) {
          return;
        }
      }
      Thread.sleep(COUNT_POLL_INTERVAL_MILLIS);
    }

    int finalCollectionCount;
    synchronized (this) {
      finalCollectionCount = collection.size();
    }
    assertTrue(
        finalCollectionCount >= count,
        "Expected at least "
            + count
            + " "
            + description
            + " within "
            + timeout
            + "ms, but got "
            + finalCollectionCount);
  }
}
