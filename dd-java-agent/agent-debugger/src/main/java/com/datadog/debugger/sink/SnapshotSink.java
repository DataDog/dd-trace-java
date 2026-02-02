package com.datadog.debugger.sink;

import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.uploader.BatchUploader;
import com.datadog.debugger.util.ExceptionHelper;
import com.datadog.debugger.util.SnapshotPruner;
import datadog.http.client.HttpUrl;
import datadog.trace.api.Config;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.AgentThreadFactory;
import datadog.trace.util.TagsHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects snapshots that needs to be sent to the backend the notion of high/low rate is used to
 * control the flush interval low rate is used for snapshots with Captures (deep variable capture,
 * captureSnapshot = true) high rate is used for snapshots without Captures (dynamic templated logs)
 */
public class SnapshotSink {
  private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotSink.class);
  public static final int MAX_SNAPSHOT_SIZE = 1024 * 1024;
  public static final int LOW_RATE_CAPACITY = 1024;
  static final int HIGH_RATE_MIN_FLUSH_INTERVAL_MS = 1;
  static final int HIGH_RATE_MAX_FLUSH_INTERVAL_MS = 100;
  private static final int HIGH_RATE_CAPACITY = 1024;
  private static final int HIGH_RATE_10_PERCENT_CAPACITY = HIGH_RATE_CAPACITY / 10;
  private static final int HIGH_RATE_25_PERCENT_CAPACITY = HIGH_RATE_CAPACITY / 4;
  private static final int HIGH_RATE_75_PERCENT_CAPACITY = HIGH_RATE_CAPACITY * 3 / 4;
  static final long HIGH_RATE_STEP_SIZE = 10;
  public static final BatchUploader.RetryPolicy RETRY_POLICY = new BatchUploader.RetryPolicy(0);

  // low rate queue (aka snapshots)
  private final BlockingQueue<Snapshot> lowRateSnapshots =
      new ArrayBlockingQueue<>(LOW_RATE_CAPACITY);
  // high rate queue (aka dynamic templated logs)
  private final BlockingQueue<Snapshot> highRateSnapshots =
      new ArrayBlockingQueue<>(HIGH_RATE_CAPACITY);
  private final String serviceName;
  private final int batchSize;
  private final String tags;
  // uploader for low rate (aka snapshots)
  private final BatchUploader lowRateUploader;
  // uploader for high rate (aka dynamic templated logs)
  private final BatchUploader highRateUploader;
  private final AgentTaskScheduler highRateScheduler =
      new AgentTaskScheduler(AgentThreadFactory.AgentThread.DEBUGGER_SNAPSHOT_SERIALIZER);
  private final AtomicBoolean started = new AtomicBoolean();
  private volatile AgentTaskScheduler.Scheduled<SnapshotSink> highRateScheduled;
  private volatile long currentHighRateFlushInterval = HIGH_RATE_MAX_FLUSH_INTERVAL_MS;

  public SnapshotSink(
      Config config, String tags, BatchUploader lowRateUploader, BatchUploader highRateUploader) {
    this.serviceName = TagsHelper.sanitize(config.getServiceName());
    this.batchSize = config.getDynamicInstrumentationUploadBatchSize();
    this.tags = tags;
    this.lowRateUploader = lowRateUploader;
    this.highRateUploader = highRateUploader;
  }

  public void start() {
    if (started.compareAndSet(false, true)) {
      highRateScheduled =
          highRateScheduler.scheduleAtFixedRate(
              this::highRateFlush, this, 0, currentHighRateFlushInterval, TimeUnit.MILLISECONDS);
    }
  }

  public void stop() {
    AgentTaskScheduler.Scheduled<SnapshotSink> localScheduled = this.highRateScheduled;
    if (localScheduled != null) {
      localScheduled.cancel();
    }
    lowRateUploader.shutdown();
    highRateUploader.shutdown();
    started.set(false);
  }

  public void lowRateFlush(String tags) {
    List<String> snapshots = getSerializedSnapshots(lowRateSnapshots, batchSize);
    if (snapshots.isEmpty()) {
      return;
    }
    uploadPayloads(lowRateUploader, snapshots, tags);
  }

  public void highRateFlush(SnapshotSink ignored) {
    do {
      List<String> snapshots = getSerializedSnapshots(highRateSnapshots, HIGH_RATE_CAPACITY);
      if (snapshots.isEmpty()) {
        backOffHighRateFlush();
        return;
      }
      int count = snapshots.size();
      reconsiderHighRateFlushInterval(count);
      uploadPayloads(highRateUploader, snapshots, tags);
    } while (!highRateSnapshots.isEmpty());
  }

  public HttpUrl getUrl() {
    return lowRateUploader.getUrl();
  }

  public long remainingCapacity() {
    return lowRateSnapshots.remainingCapacity();
  }

  public boolean addLowRate(Snapshot snapshot) {
    return lowRateSnapshots.offer(snapshot);
  }

  public boolean addHighRate(Snapshot snapshot) {
    return highRateSnapshots.offer(snapshot);
  }

  long getCurrentHighRateFlushInterval() {
    return currentHighRateFlushInterval;
  }

  private void backOffHighRateFlush() {
    long interval = currentHighRateFlushInterval;
    currentHighRateFlushInterval =
        Math.min(interval + HIGH_RATE_STEP_SIZE, HIGH_RATE_MAX_FLUSH_INTERVAL_MS);
    if (interval != currentHighRateFlushInterval) {
      highRateReschedule();
    }
  }

  private void reconsiderHighRateFlushInterval(int snapshotCount) {
    long interval = currentHighRateFlushInterval;
    if (snapshotCount == HIGH_RATE_CAPACITY) {
      currentHighRateFlushInterval = HIGH_RATE_MIN_FLUSH_INTERVAL_MS;
    } else if (snapshotCount > HIGH_RATE_75_PERCENT_CAPACITY) {
      currentHighRateFlushInterval = Math.max(interval / 4, HIGH_RATE_MIN_FLUSH_INTERVAL_MS);
    } else if (snapshotCount > HIGH_RATE_25_PERCENT_CAPACITY) {
      currentHighRateFlushInterval = Math.max(interval / 2, HIGH_RATE_MIN_FLUSH_INTERVAL_MS);
    } else if (snapshotCount > HIGH_RATE_10_PERCENT_CAPACITY) {
      currentHighRateFlushInterval =
          Math.max(interval - HIGH_RATE_STEP_SIZE, HIGH_RATE_MIN_FLUSH_INTERVAL_MS);
    }
    if (interval != currentHighRateFlushInterval) {
      highRateReschedule();
    }
  }

  private void highRateReschedule() {
    if (!started.get()) {
      return;
    }
    AgentTaskScheduler.Scheduled<SnapshotSink> localScheduled = this.highRateScheduled;
    if (localScheduled != null) {
      localScheduled.cancel();
    }
    LOGGER.debug(
        "Rescheduling high rate debugger sink flush to {}ms", currentHighRateFlushInterval);
    this.highRateScheduled =
        highRateScheduler.scheduleAtFixedRate(
            this::highRateFlush,
            this,
            currentHighRateFlushInterval,
            currentHighRateFlushInterval,
            TimeUnit.MILLISECONDS);
  }

  private List<String> getSerializedSnapshots(BlockingQueue<Snapshot> queue, int localBatchSize) {
    List<Snapshot> snapshots = new ArrayList<>();
    if (queue.remainingCapacity() == 0) {
      localBatchSize = queue.size();
    }
    queue.drainTo(snapshots, localBatchSize);
    List<String> serializedSnapshots = new ArrayList<>();
    boolean largeBatch = snapshots.size() > 10;
    if (largeBatch) {
      LOGGER.debug("Drained {} snapshots, remains {}", snapshots.size(), queue.size());
    }
    for (Snapshot snapshot : snapshots) {
      try {
        String strSnapshot = serializeSnapshot(serviceName, snapshot);
        serializedSnapshots.add(strSnapshot);
        if (!largeBatch) {
          LOGGER.debug("Sending snapshot for probe: {}", snapshot.getProbe().getId());
        }
      } catch (Exception e) {
        ExceptionHelper.logException(LOGGER, e, "Error during snapshot serialization:");
      }
    }
    return serializedSnapshots;
  }

  private String serializeSnapshot(String serviceName, Snapshot snapshot) {
    snapshot.getId(); // Ensure id is generated
    String str = DebuggerAgent.getSnapshotSerializer().serializeSnapshot(serviceName, snapshot);
    String prunedStr = SnapshotPruner.prune(str, MAX_SNAPSHOT_SIZE, 4);
    if (prunedStr.length() != str.length()) {
      LOGGER.debug(
          "serializing snapshot breached 1MB limit, reducing size from {} -> {}",
          str.length(),
          prunedStr.length());
    }
    return prunedStr;
  }

  private static void uploadPayloads(BatchUploader uploader, List<String> payloads, String tags) {
    List<byte[]> batches = IntakeBatchHelper.createBatches(payloads);
    for (byte[] batch : batches) {
      uploader.upload(batch, tags);
    }
  }
}
