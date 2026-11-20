package datadog.trace.agent.test.datastreams;

import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.core.datastreams.DatastreamsPayloadWriter;
import datadog.trace.core.datastreams.StatsBucket;
import datadog.trace.core.datastreams.StatsGroup;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class RecordingDataStreamsWriter implements DatastreamsPayloadWriter {
  private final List<StatsBucket> payloads = new ArrayList<>();
  private final List<StatsGroup> groups = new ArrayList<>();
  private final List<DataStreamsTags> backlogs = new ArrayList<>();

  @Override
  public synchronized void writePayload(Collection<StatsBucket> data, String serviceNameOverride) {
    payloads.addAll(data);
    for (StatsBucket bucket : data) {
      groups.addAll(bucket.getGroups());
      if (bucket.getBacklogs() != null) {
        for (Map.Entry<DataStreamsTags, Long> backlog : bucket.getBacklogs()) {
          backlogs.add(backlog.getKey());
        }
      }
    }
  }

  public synchronized List<StatsBucket> getPayloads() {
    return Collections.unmodifiableList(new ArrayList<>(payloads));
  }

  public synchronized List<StatsGroup> getGroups() {
    return Collections.unmodifiableList(new ArrayList<>(groups));
  }

  public synchronized List<DataStreamsTags> getBacklogs() {
    return Collections.unmodifiableList(new ArrayList<>(backlogs));
  }

  public synchronized void clear() {
    payloads.clear();
    groups.clear();
    backlogs.clear();
  }
}
