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
  private final Map<String, AtomicLong> histoMap = new ConcurrentHashMap<>();
  private final EventType exceptionCountEventType;
  private final int maxTopItems;
  private final boolean forceEnabled;

  @FunctionalInterface
  interface ValueVisitor {
    void visit(String key, long value);
  }

  ExceptionHistogram(final Config config) {
    this(config.getProfilingExceptionHistoMax(), false);
  }

  ExceptionHistogram(final int maxTopItems, final boolean forceEnabled) {
    this.maxTopItems = maxTopItems;
    exceptionCountEventType = EventType.getEventType(ExceptionCountEvent.class);
    this.forceEnabled = forceEnabled;

    FlightRecorder.addPeriodicEvent(ExceptionCountEvent.class, this::emit);
  }

  private void emit() {
    if (forceEnabled || exceptionCountEventType.isEnabled()) {
      processAndReset(this::newExceptionCountEvent);
    }
  }

  private void newExceptionCountEvent(final String type, final long count) {
    final ExceptionCountEvent event = new ExceptionCountEvent(type, count);
    if (event.shouldCommit()) {
      event.commit();
    }
  }

  public boolean record(final Exception exception) {
    if (exception == null) {
      return false;
    }
    return record(exception.getClass().getCanonicalName());
  }

  boolean record(final String typeName) {
    if (typeName == null) {
      return false;
    }
    if (forceEnabled || exceptionCountEventType.isEnabled()) {
      final boolean[] firstHit = new boolean[] {false};
      histoMap
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

      return firstHit[0];
    }
    return false;
  }

  void processAndReset(final ValueVisitor processor) {
    Stream<Map.Entry<String, Long>> items =
        histoMap
            .entrySet()
            .stream()
            .map(e -> entry(e.getKey(), e.getValue().getAndSet(0L)))
            .filter(e -> e.getValue() != 0)
            .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()));
    histoMap.entrySet().removeIf(e -> e.getValue().get() == 0L);
    if (maxTopItems > 0) {
      items = items.limit(maxTopItems);
    }
    items.forEach(e -> processor.visit(e.getKey(), e.getValue()));
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
