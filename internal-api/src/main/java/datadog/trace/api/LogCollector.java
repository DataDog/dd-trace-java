package datadog.trace.api;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

public class LogCollector {
  public static Marker SEND_TELEMETRY = MarkerFactory.getMarker("SEND_TELEMETRY");
  private static final int DEFAULT_MAX_CAPACITY = 1024;
  private final Map<RawLogMessage, AtomicLong> rawLogMessages;
  private final int maxCapacity;

  public static class Holder {
    public static final LogCollector INSTANCE = new LogCollector();
  }

  public static LogCollector get() {
    return Holder.INSTANCE;
  }

  LogCollector() {
    this(DEFAULT_MAX_CAPACITY);
  }

  // For testing purpose
  LogCollector(int maxCapacity) {
    this.maxCapacity = maxCapacity;
    this.rawLogMessages = new ConcurrentHashMap<>(maxCapacity);
  }

  public void addLogMessage(String logLevel, String message, Throwable throwable) {
    RawLogMessage rawLogMessage =
        new RawLogMessage(logLevel, message, throwable, System.currentTimeMillis());

    if (rawLogMessages.size() < maxCapacity) {
      AtomicLong count = rawLogMessages.computeIfAbsent(rawLogMessage, k -> new AtomicLong(0));
      count.incrementAndGet();
    }
  }

  public Collection<RawLogMessage> drain() {
    if (!rawLogMessages.isEmpty()) {
      List<RawLogMessage> list = new LinkedList<>();

      for (Iterator<Map.Entry<RawLogMessage, AtomicLong>> iterator =
              rawLogMessages.entrySet().iterator();
          iterator.hasNext(); ) {
        Map.Entry<RawLogMessage, AtomicLong> entry = iterator.next();

        RawLogMessage logMessage = entry.getKey();
        AtomicLong count = entry.getValue();

        if (count.get() > 1) {
          // Add number of duplications at the end of the message
          logMessage.message =
              String.format(
                  "%s, {%d} additional messages skipped", logMessage.message, count.get());
        }
        list.add(logMessage);

        iterator.remove();
      }

      return list;
    }
    return Collections.emptyList();
  }

  public static class RawLogMessage {
    public String message; // message can be modified by grouping
    public final String logLevel;
    public final Throwable throwable;
    public final long timestamp;

    public RawLogMessage(String logLevel, String message, Throwable throwable, long timestamp) {
      this.logLevel = logLevel;
      this.message = message;
      this.throwable = throwable;
      this.timestamp = timestamp;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      RawLogMessage that = (RawLogMessage) o;
      return Objects.equals(logLevel, that.logLevel)
          && Objects.equals(message, that.message)
          && Objects.equals(throwable, that.throwable);
    }

    @Override
    public int hashCode() {
      return Objects.hash(logLevel, message, throwable);
    }
  }
}
