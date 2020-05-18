package com.datadog.profiling.mlt;

import datadog.trace.core.util.NoneThreadStackProvider;
import datadog.trace.core.util.ThreadStackAccess;
import datadog.trace.core.util.ThreadStackProvider;
import datadog.trace.profiling.Session;
import java.lang.management.ThreadInfo;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JMXSession implements Session {
  private final String id;
  private final List<Long> threadIds;
  private final StackTraceSink sink;
  private final ThreadStackProvider provider;
  private final Map<Long, JMXSession> sessions;
  private final AtomicInteger refCount = new AtomicInteger();
  private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

  public JMXSession(String id, long threadId, StackTraceSink sink, Map<Long, JMXSession> sessions) {
    this.id = id;
    this.threadIds = Collections.singletonList(threadId);
    this.sink = sink;
    this.sessions = sessions;
    provider = ThreadStackAccess.getCurrentThreadStackProvider();
    if (provider instanceof NoneThreadStackProvider) {
      log.warn("ThreadStack provider is oo op. It will not provide thread stacks.");
    }
    start();
  }

  public void close() {
    sessions.computeIfPresent(threadIds.get(0), this::closeSession);
  }

  private JMXSession closeSession(Long key, JMXSession jmxSession) {
    int current = jmxSession.decRefCount();
    if (current == 0) {
      executor.shutdown();
      return null;
    }
    return jmxSession;
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
    List<ThreadInfo> threadInfos = provider.getThreadInfos(threadIds);
    sink.dump(id, threadInfos);
  }
}
