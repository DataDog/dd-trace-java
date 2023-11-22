package datadog.trace.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

public class LogCollector {
  public static Marker SEND_TELEMETRY = MarkerFactory.getMarker("SEND_TELEMETRY");
  private static final int DEFAULT_MAX_CAPACITY = 1024;
  private final Map<RawLogMessage, AtomicInteger> rawLogMessages;
  private final int maxCapacity;

  private static class Holder {
    private static final LogCollector INSTANCE = new LogCollector();
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
      AtomicInteger count = rawLogMessages.computeIfAbsent(rawLogMessage, k -> new AtomicInteger());
      count.incrementAndGet();
    }
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
      logMessage.count = entry.getValue().get();

      iterator.remove();

      list.add(logMessage);
    }

    return list;
  }

  public static class RawLogMessage {
    public final String messageOriginal;
    public final String logLevel;
    public final Throwable throwable;
    public final long timestamp;
    public int count;

    public RawLogMessage(String logLevel, String message, Throwable throwable, long timestamp) {
      this.logLevel = logLevel;
      this.messageOriginal = message;
      this.throwable = throwable;
      this.timestamp = timestamp;
    }

    public String message() {
      if (count > 1) {
        return messageOriginal + ", {" + count + "} additional messages skipped";
      }
      return messageOriginal;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      RawLogMessage that = (RawLogMessage) o;
      return Objects.equals(logLevel, that.logLevel)
          && Objects.equals(messageOriginal, that.messageOriginal)
          && Objects.equals(throwable, that.throwable);
    }

    @Override
    public int hashCode() {
      return Objects.hash(logLevel, messageOriginal, throwable);
    }
  }
}
