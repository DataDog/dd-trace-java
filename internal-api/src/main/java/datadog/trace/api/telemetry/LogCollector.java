package datadog.trace.api.telemetry;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

public class LogCollector {
  public static final Marker SEND_TELEMETRY = MarkerFactory.getMarker("SEND_TELEMETRY");
  public static final Marker EXCLUDE_TELEMETRY = MarkerFactory.getMarker("EXCLUDE_TELEMETRY");
  private static final int DEFAULT_MAX_CAPACITY = 10;
  private static final LogCollector INSTANCE = new LogCollector();
  private final Map<RawLogMessage, AtomicInteger> rawLogMessages;
  private final int maxCapacity;

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
    this.maxCapacity = maxCapacity;
    this.rawLogMessages = new ConcurrentHashMap<>(maxCapacity);
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
    if (rawLogMessages.size() >= maxCapacity) {
      // TODO: We could emit a metric for dropped logs.
      return;
    }
    RawLogMessage rawLogMessage =
        new RawLogMessage(logLevel, message, throwable, tags, System.currentTimeMillis() / 1000);
    AtomicInteger count = rawLogMessages.computeIfAbsent(rawLogMessage, k -> new AtomicInteger());
    count.incrementAndGet();
  }

  public Collection<RawLogMessage> drain() {
    if (rawLogMessages.isEmpty()) {
      return Collections.emptyList();
    }

    List<RawLogMessage> list = new ArrayList<>();
    Iterator<Map.Entry<RawLogMessage, AtomicInteger>> iterator =
        rawLogMessages.entrySet().iterator();

    while (iterator.hasNext()) {
      Map.Entry<RawLogMessage, AtomicInteger> entry = iterator.next();
      RawLogMessage logMessage = entry.getKey();
      // XXX: There might be lost writers to the counters under concurrency if another thread
      // increments it
      //      while we are reading it here. At the moment, we are not overdoing this to prevent some
      // counter losses.
      logMessage.count = entry.getValue().get();
      iterator.remove();
      list.add(logMessage);
    }

    return list;
  }

  public static class RawLogMessage {
    public final String message;
    public final String logLevel;
    public final Throwable throwable;
    public final String tags;
    public final long timestamp;
    public int count;

    public RawLogMessage(
        String logLevel, String message, Throwable throwable, String tags, long timestamp) {
      this.logLevel = logLevel;
      this.message = message;
      this.throwable = throwable;
      this.tags = tags;
      this.timestamp = timestamp;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      RawLogMessage that = (RawLogMessage) o;
      return Objects.equals(logLevel, that.logLevel)
          && Objects.equals(message, that.message)
          && Objects.equals(
              throwable == null ? null : throwable.getClass(),
              that.throwable == null ? null : that.throwable.getClass())
          && Objects.deepEquals(
              throwable == null ? null : throwable.getStackTrace(),
              that.throwable == null ? null : that.throwable.getStackTrace());
    }

    @Override
    public int hashCode() {
      return Objects.hash(logLevel, message, throwable == null ? null : throwable.getClass());
    }
  }
}
