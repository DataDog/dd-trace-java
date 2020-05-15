package com.datadog.profiling.mlt;

import datadog.trace.profiling.Session;
import datadog.trace.profiling.SessionFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JMXSessionFactory implements SessionFactory {
  private final StackTraceSink sink;
  private final Map<Long, JMXSession> jmxSessions = new ConcurrentHashMap<>();

  public JMXSessionFactory(StackTraceSink sink) {
    this.sink = sink;
  }

  public Session createSession(String id, Thread thread) {
    long threadId = thread.getId();
    JMXSession session = jmxSessions.computeIfAbsent(threadId, key -> new JMXSession(id, threadId, sink, jmxSessions));
    session.incRefCount();
    return session;
  }
}
