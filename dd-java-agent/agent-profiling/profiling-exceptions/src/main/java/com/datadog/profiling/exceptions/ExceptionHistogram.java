package com.datadog.profiling.exceptions;

import datadog.trace.api.Config;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ExceptionHistogram {
  private final Map<String, AtomicLong> histogram = new ConcurrentHashMap<>();
  private final int maxTopItems;
  private final int maxSize;
  private final EventType exceptionCountEventType;
  private final Runnable eventHook;

  ExceptionHistogram(final Config config) {
    maxTopItems = config.getProfilingExceptionHistogramTopItems();
    maxSize = config.getProfilingExceptionHistogramMaxCollectionSize();
    exceptionCountEventType = EventType.getEventType(ExceptionCountEvent.class);
    eventHook = this::emit;
    FlightRecorder.addPeriodicEvent(ExceptionCountEvent.class, eventHook);
  }

  public void deregister() {
    FlightRecorder.removePeriodicEvent(eventHook);
  }

  public boolean record(final Exception exception) {
    if (exception == null) {
      return false;
    }
    return record(exception.getClass().getCanonicalName());
  }

  private boolean record(final String typeName) {
    if (exceptionCountEventType.isEnabled()) {
      if (histogram.size() >= maxSize && !histogram.containsKey(typeName)) {
        log.debug(
            "Histogram is too big ({}), skipping adding new entry: {}: {}",
            histogram.size(),
            typeName,
            histogram);
        return false;
      }

      final boolean[] firstHit = new boolean[] {false};
      histogram
          .computeIfAbsent(
              typeName,
              k -> {
                try {
                  return new AtomicLong();
                } finally {
                  firstHit[0] = true;
                }
              })
          .incrementAndGet();

      // FIXME: this 'first hit' logic is confusing and untested
      return firstHit[0];
    }
    return false;
  }

  private void emit() {
    if (!exceptionCountEventType.isEnabled()) {
      return;
    }

    Stream<Map.Entry<String, Long>> items =
        histogram
            .entrySet()
            .stream()
            .map(e -> entry(e.getKey(), e.getValue().getAndSet(0L)))
            .filter(e -> e.getValue() != 0)
            .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()));

    if (maxTopItems > 0) {
      items = items.limit(maxTopItems);
    }

    items.forEach(e -> createAndCommitEvent(e.getKey(), e.getValue()));

    // Stream is 'materialized' by `forEach` call above so we have to do clean up after that
    // Otherwise we would keep entries for one extra iteration
    histogram.entrySet().removeIf(e -> e.getValue().get() == 0L);
  }

  private void createAndCommitEvent(final String type, final long count) {
    final ExceptionCountEvent event = new ExceptionCountEvent(type, count);
    if (event.shouldCommit()) {
      event.commit();
    }
  }

  private static <K, V> Map.Entry<K, V> entry(final K key, final V value) {
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
      public V setValue(final V v) {
        return value;
      }
    };
  }
}
