package com.datadog.profiling.mlt;

import datadog.trace.core.util.NoneThreadStackProvider;
import datadog.trace.core.util.ThreadStackAccess;
import datadog.trace.core.util.ThreadStackProvider;
import datadog.trace.profiling.Session;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JMXSession implements Session {
  private final Set<Long> threadsIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
  private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
  private static final AtomicInteger refCount = new AtomicInteger();
  private final StackTraceSink sink;
  private final Consumer<Set<Long>> cleanup;
  private final ThreadStackProvider provider;

  public JMXSession(StackTraceSink sink, Consumer<Set<Long>> cleanup) {
    this.sink = sink;
    this.cleanup = cleanup;
    provider = ThreadStackAccess.getCurrentThreadStackProvider();
    if (provider == NoneThreadStackProvider.INSTANCE) {
      log.warn("ThreadStack provider is oo op. It will not provide thread stacks.");
    }
    start();
  }

  public void addThread(Thread thread) {
    threadsIds.add(thread.getId());
  }

  public void close() {
    int current = decRefCount();
    if (current == 0) {
      executor.shutdown();
      cleanup.accept(threadsIds);
    }
  }

  void incRefCount() {
    refCount.getAndIncrement();
  }

  int decRefCount() {
    return refCount.decrementAndGet();
  }

  private void start() {
    // TODO period as parameter
    executor.scheduleAtFixedRate(this::sample, 0, 10, TimeUnit.MILLISECONDS);
  }

  private void sample() {
    List<StackTraceElement[]> stackTraces = new ArrayList<>();
    provider.getStackTrace(threadsIds, stackTraces);
    sink.dump(stackTraces);
  }
}
