package com.datadog.profiling.mlt;

import datadog.trace.profiling.Session;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JMXSession implements Session {
  private final String id;
  private final long[] threadIds;
  private final Map<Long, JMXSession> sessions;
  private final AtomicInteger refCount = new AtomicInteger();

  public JMXSession(String id, long threadId, Map<Long, JMXSession> sessions) {
    this.id = id;
    this.threadIds = new long[] { threadId };
    this.sessions = sessions;
  }

  public void close() {
    sessions.computeIfPresent(threadIds[0], this::closeSession);
  }

  public String getId() {
    return id;
  }

  private JMXSession closeSession(Long key, JMXSession jmxSession) {
    int current = jmxSession.decRefCount();
    if (current == 0) {
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
}
