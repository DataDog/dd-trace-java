package datadog.trace.api.telemetry;

import static datadog.trace.util.ConcurrentHashtable.bucketAt;
import static datadog.trace.util.ConcurrentHashtable.bucketIndex;
import static datadog.trace.util.ConcurrentHashtable.estimateSize;
import static datadog.trace.util.ConcurrentHashtable.getTableWriteLock;
import static datadog.trace.util.ConcurrentHashtable.insertReserved;
import static datadog.trace.util.ConcurrentHashtable.isFull;
import static datadog.trace.util.LongHashingUtils.hash;

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
  private final ConcurrentHashtable.State<RawLogMessage> rawLogMessages;

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
    if (isFull(rawLogMessages)) {
      // TODO: We could emit a metric for dropped logs.
      return;
    }

    long keyHash = RawLogMessage.computeHash(logLevel, message, throwable);
    int index = bucketIndex(rawLogMessages.buckets, keyHash);
    RawLogMessage rawLogMessage = find(index, keyHash, logLevel, message, throwable);
    if (rawLogMessage != null) {
      rawLogMessage.increment();
      return;
    }

    synchronized (getTableWriteLock(rawLogMessages)) {
      rawLogMessage = find(index, keyHash, logLevel, message, throwable);
      if (rawLogMessage != null) {
        rawLogMessage.increment();
        return;
      }
      if (isFull(rawLogMessages)) {
        return;
      }

      rawLogMessage =
          new RawLogMessage(logLevel, message, throwable, tags, System.currentTimeMillis() / 1000);
      if (rawLogMessages.sizeManager.tryReserve()) {
        insertReserved(rawLogMessages, keyHash, rawLogMessage);
      }
    }
  }

  public Collection<RawLogMessage> drain() {
    int size = estimateSize(rawLogMessages);
    if (size == 0) {
      return Collections.emptyList();
    }

    List<RawLogMessage> list = new ArrayList<>(size);
    ConcurrentHashtable.drain(
        rawLogMessages,
        list,
        (drained, logMessage) -> {
          // A writer that found this entry before drain detached it can still increment too late.
          logMessage.snapshotCount();
          drained.add(logMessage);
        });
    return list;
  }

  @Nullable
  private RawLogMessage find(
      int index, long keyHash, String logLevel, String message, @Nullable Throwable throwable) {
    StackTraceElement[] stackTrace = null;
    for (RawLogMessage entry = bucketAt(rawLogMessages, index);
        entry != null;
        entry = entry.next()) {
      if (entry.keyHash != keyHash
          || !Objects.equals(logLevel, entry.logLevel)
          || !Objects.equals(message, entry.message)) {
        continue;
      }
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

  public static final class RawLogMessage extends ConcurrentHashtable.Entry {
    private static final AtomicIntegerFieldUpdater<RawLogMessage> DEDUP_COUNT =
        AtomicIntegerFieldUpdater.newUpdater(RawLogMessage.class, "dedupCount");

    public final String message;
    public final String logLevel;
    public final Throwable throwable;
    public final String tags;
    public final long timestamp;
    public int count;

    private volatile int dedupCount = 1;
    private StackTraceElement[] cachedStackTrace = null;

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

      // DQH - getStackTrace makes a defensive copy, so getStackTrace can become a significant
      // source of allocation
      // In the worst case of a hot exception, we'll constantly call hashCode & equals to
      // check against the key stored in the map, so avoiding repeated allocation on each
      // comparison does provide a measurable gain
      StackTraceElement[] stackTrace = cachedStackTrace;
      if (stackTrace != null) return stackTrace;

      cachedStackTrace = stackTrace = throwable.getStackTrace();
      return stackTrace;
    }

    private void increment() {
      DEDUP_COUNT.incrementAndGet(this);
    }

    private void snapshotCount() {
      count = DEDUP_COUNT.get(this);
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
