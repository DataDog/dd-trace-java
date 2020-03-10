package com.datadog.profiling.exceptions;

import datadog.trace.api.Config;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ExceptionHistogram {
  private final Map<String, LongAdder> histoMap = new ConcurrentHashMap<>();
  private final EventType exceptionCountEventType;
  private final int maxTopItems;
  private final boolean forceEnabled;

  @FunctionalInterface
  interface ValueVisitor {
    void visit(String key, long value);
  }

  ExceptionHistogram(Config config) {
    this(config.getProfilingExceptionHistoMax(), false);
  }

  ExceptionHistogram(int maxTopItems, boolean forceEnabled) {
    this.maxTopItems = maxTopItems;
    this.exceptionCountEventType = EventType.getEventType(ExceptionCountEvent.class);
    this.forceEnabled = forceEnabled;

    FlightRecorder.addPeriodicEvent(ExceptionCountEvent.class, this::emit);
  }

  private void emit() {
    if (forceEnabled || exceptionCountEventType.isEnabled()) {
      processAndReset(this::newExceptionCountEvent);
    }
  }

  private void newExceptionCountEvent(String type, long count) {
    ExceptionCountEvent event = new ExceptionCountEvent(type, count);
    if (event.shouldCommit()) {
      event.commit();
    }
  }

  public boolean record(Exception exception) {
    if (exception == null) {
      return false;
    }
    return record(exception.getClass().getCanonicalName());
  }

  boolean record(String typeName) {
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

  void processAndReset(ValueVisitor processor) {
    Stream<Map.Entry<String, Long>> items =
        histoMap
            .entrySet()
            .stream()
            .map(e -> entry(e.getKey(), e.getValue().sumThenReset()))
            .filter(e -> e.getValue() != 0)
            .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()));
    if (maxTopItems > 0) {
      items = items.limit(maxTopItems);
    }
    items.forEach(e -> processor.visit(e.getKey(), e.getValue()));
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
