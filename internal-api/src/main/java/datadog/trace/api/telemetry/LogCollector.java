package datadog.trace.api.telemetry;

import datadog.trace.util.ConcurrentHashtable;
import datadog.trace.util.HashingUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
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
    this.rawLogMessages = ConcurrentHashtable.createBounded(RawLogMessage.class, maxCapacity);
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
    long keyHash = RawLogMessage.hash(logLevel, message, throwable);

    // Lock-free scan first: most calls are re-observations of an already-seen message, so this
    // avoids paying for a reservation and a RawLogMessage allocation on the common path.
    for (RawLogMessage existing = ConcurrentHashtable.bucketFor(rawLogMessages, keyHash);
        existing != null;
        existing = existing.next()) {
      if (existing.keyHash == keyHash && existing.matchesKey(logLevel, message, throwable)) {
        existing.count.incrementAndGet();
        return;
      }
    }

    try (ConcurrentHashtable.Reservation<RawLogMessage> reservation =
        ConcurrentHashtable.reserve(rawLogMessages)) {
      // TODO: We could emit a metric for dropped logs when the reservation is empty (table full).
      RawLogMessage rawLogMessage =
          reservation.tryGetOrInsertOrNull(RawLogMessage::new, logLevel, message, throwable, tags);
      if (rawLogMessage != null) {
        rawLogMessage.count.incrementAndGet();
      }
    }
  }

  public Collection<RawLogMessage> drain() {
    if (ConcurrentHashtable.estimateSize(rawLogMessages) == 0) {
      return Collections.emptyList();
    }

    List<RawLogMessage> list = new ArrayList<>(ConcurrentHashtable.estimateSize(rawLogMessages));
    ConcurrentHashtable.drain(rawLogMessages, list::add);
    return list;
  }

  public static final class RawLogMessage extends ConcurrentHashtable.Entry<RawLogMessage> {
    public final String message;
    public final String logLevel;
    public final Throwable throwable;
    public final String tags;
    public final long timestamp;
    public final AtomicInteger count = new AtomicInteger();

    private StackTraceElement[] cachedStackTrace = null;

    public RawLogMessage(
        String logLevel, String message, Throwable throwable, @Nullable String tags) {
      super(hash(logLevel, message, throwable));
      this.logLevel = logLevel;
      this.message = message;
      this.throwable = throwable;
      this.tags = tags;
      this.timestamp = System.currentTimeMillis() / 1000;
    }

    static long hash(String logLevel, String message, @Nullable Throwable throwable) {
      return HashingUtils.hash(logLevel, message, throwable == null ? null : throwable.getClass());
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

    private boolean matchesKey(String logLevel, String message, @Nullable Throwable throwable) {
      if (!Objects.equals(this.logLevel, logLevel)) return false;
      if (!Objects.equals(this.message, message)) return false;

      if (this.throwable == throwable) {
        // DQH - While this path may seem unlikely, it does happen if the JVM fast
        // throws optimization kicks-in (for NPE, etc), so this case is worth optimizing.

        // This also covers the case where both throwables are null
        return true;
      } else if (this.throwable != null && throwable != null) {
        // Both have a throwable perform a deeper comparison
        return this.throwable.getClass().equals(throwable.getClass())
            && Objects.deepEquals(stackTrace(), throwable.getStackTrace());
      } else {
        // One has an exception & the other doesn't, not equal
        return false;
      }
    }

    @Override
    public boolean matches(@Nonnull RawLogMessage other) {
      if (!Objects.equals(logLevel, other.logLevel)) return false;
      if (!Objects.equals(message, other.message)) return false;

      if (throwable == other.throwable) {
        return true;
      } else if (throwable != null && other.throwable != null) {
        return throwable.getClass().equals(other.throwable.getClass())
            && Objects.deepEquals(stackTrace(), other.stackTrace());
      } else {
        return false;
      }
    }
  }
}
