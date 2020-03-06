package datadog.trace.bootstrap.instrumentation.jfr.exceptions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;

public class ExceptionHistogram {
  private static final Map<String, LongAdder> HISTO_MAP = new ConcurrentHashMap<>();

  private static volatile EventType EXCEPTION_COUNT_EVENT_TYPE = null;

  @FunctionalInterface
  private interface HistoProcessor {
    void process(String key, long value);
  }

  public static void init() {
    EXCEPTION_COUNT_EVENT_TYPE = EventType.getEventType(ExceptionCountEvent.class);
    FlightRecorder.addPeriodicEvent(
        ExceptionCountEvent.class,
        () -> {
          if (EXCEPTION_COUNT_EVENT_TYPE != null && EXCEPTION_COUNT_EVENT_TYPE.isEnabled()) {
            dump(
                (k, v) -> {
                  ExceptionCountEvent event = new ExceptionCountEvent(k, v);
                  if (event.shouldCommit()) {
                    event.commit();
                  }
                },
                false);
          }
        });
  }

  public static boolean record(Exception exception) {
    return record(exception.getClass().getCanonicalName());
  }

  public static boolean record(String typeName) {
    if (EXCEPTION_COUNT_EVENT_TYPE != null && EXCEPTION_COUNT_EVENT_TYPE.isEnabled()) {
      final boolean[] firstHit = new boolean[] {false};
      HISTO_MAP
          .computeIfAbsent(
              typeName,
              k -> {
                try {
                  return new LongAdder();
                } finally {
                  firstHit[0] = true;
                }
              })
          .increment();

      return firstHit[0];
    }
    return false;
  }

  public static void dump(HistoProcessor processor, boolean reset) {
    HISTO_MAP
        .entrySet()
        .stream()
        .map(e -> entry(e.getKey(), reset ? e.getValue().sumThenReset() : e.getValue().sum()))
        .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
        .limit(50)
        .forEach(e -> processor.process(e.getKey(), e.getValue()));
  }

  private static <K, V> Map.Entry<K, V> entry(K key, V value) {
    return new Map.Entry<K, V>() {
      @Override
      public K getKey() {
        return key;
      }

      @Override
      public V getValue() {
        return value;
      }

      @Override
      public V setValue(V v) {
        return value;
      }
    };
  }
}
