package com.datadog.debugger.sink;

import com.datadog.debugger.util.ExceptionHelper;
import com.datadog.debugger.util.TagsHelper;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Collects snapshots that needs to be sent to the backend */
public class SnapshotSink {
  private static final Logger LOGGER = LoggerFactory.getLogger(DebuggerSink.class);
  private static final int CAPACITY = 1000;

  private final BlockingQueue<Snapshot> snapshots = new ArrayBlockingQueue<>(CAPACITY);
  private final String serviceName;
  private final int batchSize;

  public SnapshotSink(Config config) {
    this.serviceName = TagsHelper.sanitize(config.getServiceName());
    this.batchSize = config.getDebuggerUploadBatchSize();
  }

  public List<String> getSerializedSnapshots() {
    List<Snapshot> snapshots = new ArrayList<>();
    this.snapshots.drainTo(snapshots, batchSize);
    List<String> serializedSnapshots = new ArrayList<>();
    for (Snapshot snapshot : snapshots) {
      try {
        String strSnapshot = serializeSnapshot(serviceName, snapshot);
        serializedSnapshots.add(strSnapshot);
        LOGGER.debug("Sending snapshot for probe: {}", snapshot.getProbe().getId());
      } catch (Exception e) {
        ExceptionHelper.logException(LOGGER, e, "Error during snapshot serialization:");
      }
    }
    return serializedSnapshots;
  }

  public List<Snapshot> getSnapshots() {
    List<Snapshot> snapshots = new ArrayList<>();
    this.snapshots.drainTo(snapshots, batchSize);
    return snapshots;
  }

  public long remainingCapacity() {
    return snapshots.remainingCapacity();
  }

  public boolean offer(Snapshot snapshot) {
    return snapshots.offer(snapshot);
  }

  String serializeSnapshot(String serviceName, Snapshot snapshot) {
    return DebuggerContext.serializeSnapshot(serviceName, snapshot);
  }
}
