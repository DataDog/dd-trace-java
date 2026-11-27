package datadog.trace.instrumentation.kafka_common;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kafka Connect and MirrorMaker 2 internal topics, which get no Data Streams checkpoints.
 *
 * <p>These topics hold the worker's own state (connector configs, source offsets, task statuses),
 * not application data. Checkpointing them adds a pathway per connector that nobody needs, and
 * status writes from task threads land under each connector's service name.
 *
 * <p>The Connect internal topic names are read from the worker config when the worker starts.
 * MirrorMaker 2 also writes internal topics through ordinary task producers ({@code
 * mm2-offset-syncs.*.internal}, {@code *.checkpoints.internal}, {@code heartbeats}), whose names
 * are not in the worker config, so those are matched by name. Matching by name only happens once
 * this JVM runs a Connect worker, so other applications keep checkpoints on topics with these
 * names.
 */
public final class ConnectInternalTopics {
  // Each Connect herder has three internal topics, so this is only reached by misuse.
  private static final int MAX_CONFIGURED_TOPICS = 100;

  private static final Set<String> CONFIGURED_TOPICS = ConcurrentHashMap.newKeySet();

  private static volatile boolean connectWorkerRunning;

  private ConnectInternalTopics() {}

  /** Marks this JVM as a Kafka Connect worker, which turns on matching internal topics by name. */
  public static void markConnectWorker() {
    if (!connectWorkerRunning) {
      connectWorkerRunning = true;
    }
  }

  /** Registers a Connect internal topic name read from the worker config. */
  public static void registerConfigured(String topic) {
    if (topic == null || topic.isEmpty()) {
      return;
    }
    if (CONFIGURED_TOPICS.size() < MAX_CONFIGURED_TOPICS) {
      CONFIGURED_TOPICS.add(topic);
    }
    markConnectWorker();
  }

  /** Whether DSM should skip checkpoints and backlogs for this topic. */
  public static boolean isInternal(String topic) {
    if (!connectWorkerRunning || topic == null) {
      return false;
    }
    return CONFIGURED_TOPICS.contains(topic) || matchesInternalName(topic);
  }

  static boolean matchesInternalName(String topic) {
    // Common default names for Connect's own topics, e.g. _connect-offsets or _connect_status
    if (topic.startsWith("_connect")) {
      return true;
    }
    if (topic.endsWith(".internal")) {
      // mm2-offset-syncs.<cluster>.internal and the MM2 dedicated-mode herder topics
      // mm2-configs/offsets/status.<cluster>.internal, plus <cluster>.checkpoints.internal
      return topic.startsWith("mm2-") || topic.endsWith(".checkpoints.internal");
    }
    // MM2 heartbeats, both local and replicated as <cluster>.heartbeats
    return topic.equals("heartbeats") || topic.endsWith(".heartbeats");
  }

  // Visible for testing
  static void reset() {
    CONFIGURED_TOPICS.clear();
    connectWorkerRunning = false;
  }
}
