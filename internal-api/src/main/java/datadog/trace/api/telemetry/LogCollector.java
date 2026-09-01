package datadog.trace.api.telemetry;

import static datadog.trace.util.ConcurrentHashtable.bucketAt;
import static datadog.trace.util.ConcurrentHashtable.bucketIndex;
import static datadog.trace.util.ConcurrentHashtable.estimateSize;
import static datadog.trace.util.ConcurrentHashtable.getTableWriteLock;
import static datadog.trace.util.ConcurrentHashtable.insertReserved;
import static datadog.trace.util.ConcurrentHashtable.isFull;
import static datadog.trace.util.LongHashingUtils.hash;

import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.util.ConcurrentHashtable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import javax.annotation.Nullable;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

public class LogCollector {
  public static final Marker SEND_TELEMETRY = MarkerFactory.getMarker("SEND_TELEMETRY");
  public static final Marker EXCLUDE_TELEMETRY = MarkerFactory.getMarker("EXCLUDE_TELEMETRY");
  private static final int DEFAULT_MAX_CAPACITY = 10;
  private static final LogCollector INSTANCE = new LogCollector();
  @VisibleForTesting
  final ConcurrentHashtable.State<RawLogMessage> rawLogMessages;

  public static LogCollector get() {
    return INSTANCE;
  }

  private LogCollector() {
    this(DEFAULT_MAX_CAPACITY);
  }

  @SuppressFBWarnings(
      value = "SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR",
      justification = "Usage in tests")
  LogCollector(int maxCapacity) {
    this.rawLogMessages = ConcurrentHashtable.State.createBounded(RawLogMessage.class, maxCapacity);
  }

  public void addLogMessage(String logLevel, String message, @Nullable Throwable throwable) {
    addLogMessage(logLevel, message, throwable, null);
  }

  /**
   * Queue a log message to be sent on next telemetry flush.
   *
   * @param logLevel Log level (ERROR, WARN, DEBUG). Unknown log levels will be ignored.
   * @param message Log message.
   * @param throwable Optional throwable to attach a stacktrace.
   * @param tags Optional tags to attach to the log. These are a comma-separated list, e.g.
   *     tag1:value1,tag2:value2
   */
  public void addLogMessage(
      String logLevel, String message, @Nullable Throwable throwable, @Nullable String tags) {
    long keyHash = RawLogMessage.computeHash(logLevel, message, throwable);
    int bucketIndex = bucketIndex(rawLogMessages.buckets, keyHash);
    // Fast path for a full table: reject without locking when the target bucket is populated.
    // If the bucket is empty, drain() may have detached it before releasing capacity, so continue
    // to the locked capacity check.
    if (isFull(rawLogMessages) && bucketAt(rawLogMessages, bucketIndex) != null) {
      // TODO: We could emit a metric for dropped logs.
      return;
    }

    // Fast path for duplicates: search the target bucket without locking.
    RawLogMessage rawLogMessage = find(bucketIndex, keyHash, logLevel, message, throwable);
    if (rawLogMessage != null) {
      rawLogMessage.increment();
      return;
    }

    // Slow path after a miss: repeat the lookup and capacity checks under the table write lock
    // because another writer or drain() may have changed the table.
    synchronized (getTableWriteLock(rawLogMessages)) {
      rawLogMessage = find(bucketIndex, keyHash, logLevel, message, throwable);
      if (rawLogMessage != null) {
        rawLogMessage.increment();
        return;
      }
      // Capacity may have been released by drain() or consumed by another writer while waiting.
      if (isFull(rawLogMessages)) {
        return;
      }

      // Allocate before reserving because a reservation cannot
      // be rolled back if construction fails.
      rawLogMessage =
          new RawLogMessage(logLevel, message, throwable, tags, System.currentTimeMillis() / 1000);
      // Reserve before linking so every published entry is included in the capacity count.
      if (rawLogMessages.sizeManager.tryReserve()) {
        insertReserved(rawLogMessages, keyHash, rawLogMessage);
      }
    }
  }

  /**
   * Removes all available <em>log group</em> from this collector and returns them.
   *
   * <p>The count of each returned group is captured during removal. Increments that complete
   * <em>after</em> the count is captured are not included.
   *
   * @return a collection containing the removed log groups
   */
  public Collection<RawLogMessage> drain() {
    int size = estimateSize(rawLogMessages);
    if (size == 0) {
      return Collections.emptyList();
    }

    // Note drain takes the table lock
    List<RawLogMessage> list = new ArrayList<>(size);
    ConcurrentHashtable.drain(
        rawLogMessages,
        list,
        (drained, logMessage) -> {
          // Snapshot each log group's count before adding it to the drain result.
          logMessage.snapshotCount();
          drained.add(logMessage);
        });
    return list;
  }

  /**
   * Finds a <em>log group</em> with the same <em>level</em>, <em>message</em>, and <em>throwable</em> in the selected bucket.
   *
   * <p>Note, throwables are matched by identity or by class and stack trace.
   *
   * <p>The bucket chain supports lock-free reads. A caller that inserts after a miss must repeat
   * the search under the table write lock.
   *
   * @param bucketIndex bucket selected for {@code keyHash}
   * @param keyHash precomputed hash of the level, message, and throwable class
   * @param logLevel log level to match
   * @param message message to match
   * @param throwable optional throwable to match
   * @return the matching log group, or {@code null} if none is present
   */
  @Nullable
  private RawLogMessage find(
      int bucketIndex,
      long keyHash,
      String logLevel,
      String message,
      @Nullable Throwable throwable) {
    // Start searching from given bucket, and follow the entry next links
    StackTraceElement[] stackTrace = null;
    for (RawLogMessage entry = bucketAt(rawLogMessages, bucketIndex);
        entry != null;
        entry = entry.next()) {
      if (entry.keyHash != keyHash
          || !Objects.equals(logLevel, entry.logLevel)
          || !Objects.equals(message, entry.message)) {
        continue;
      }
      // throwables are more costly to compare, check first the identity
      if (throwable == entry.throwable) {
        return entry;
      }
      if (throwable != null
          && entry.throwable != null
          && throwable.getClass().equals(entry.throwable.getClass())) {
        if (stackTrace == null) {
          stackTrace = throwable.getStackTrace();
        }
        if (Objects.deepEquals(stackTrace, entry.stackTrace())) {
          return entry;
        }
      }
    }
    return null;
  }

  /**
   * Groups equivalent log messages for a telemetry flush.
   *
   * <p>Messages are equivalent when their log level, message, and throwable type and stack trace
   * match. The first message supplies the tags and timestamp; later messages only increment the
   * occurrence count.
   */
  public static final class RawLogMessage extends ConcurrentHashtable.Entry {
    private static final AtomicIntegerFieldUpdater<RawLogMessage> LIVE_OCCURRENCE_COUNT_UPDATER =
        AtomicIntegerFieldUpdater.newUpdater(RawLogMessage.class, "liveOccurrenceCount");

    public final String message;
    public final String logLevel;
    public final Throwable throwable;
    public final String tags;
    public final long timestamp;
    /**
     * Number of equivalent log messages captured when this group was drained.
     */
    public int count;

    /**
     * Live counter equivalent log messages accumulated in this group.
     */
    private volatile int liveOccurrenceCount = 1;
    private volatile StackTraceElement[] cachedStackTrace = null;

    public RawLogMessage(
        String logLevel, String message, Throwable throwable, String tags, long timestamp) {
      super(computeHash(logLevel, message, throwable));
      this.logLevel = logLevel;
      this.message = message;
      this.throwable = throwable;
      this.tags = tags;
      this.timestamp = timestamp;
    }

    public StackTraceElement[] stackTrace() {
      if (throwable == null) return null;

      // getStackTrace() makes a defensive copy. Cache one safely published copy for concurrent
      // comparisons against equivalent throwables.
      StackTraceElement[] stackTrace = cachedStackTrace;
      if (stackTrace != null) return stackTrace;

      cachedStackTrace = stackTrace = throwable.getStackTrace();
      return stackTrace;
    }

    private void increment() {
      LIVE_OCCURRENCE_COUNT_UPDATER.incrementAndGet(this);
    }

    /**
     * Snapshot this log's live occurrence count
     */
    private void snapshotCount() {
      count = LIVE_OCCURRENCE_COUNT_UPDATER.get(this);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      RawLogMessage that = (RawLogMessage) o;

      if (!Objects.equals(logLevel, that.logLevel)) return false;
      if (!Objects.equals(message, that.message)) return false;

      if (throwable == that.throwable) {
        // DQH - While this path may seem unlikely, it does happen if the JVM fast
        // throws optimization kicks-in (for NPE, etc), so this case is worth optimizing.

        // This also covers the case where both throwables are null
        return true;
      } else if (throwable != null && that.throwable != null) {
        // Both have a throwable perform a deeper comparison
        return throwable.getClass().equals(that.throwable.getClass())
            && Objects.deepEquals(stackTrace(), that.stackTrace());
      } else {
        // One has an exception & the other doesn't, not equal
        return false;
      }
    }

    @Override
    public int hashCode() {
      return (int) keyHash;
    }

    private static long computeHash(
        String logLevel, String message, @Nullable Throwable throwable) {
      return hash(logLevel, message, throwable == null ? null : throwable.getClass());
    }
  }
}
