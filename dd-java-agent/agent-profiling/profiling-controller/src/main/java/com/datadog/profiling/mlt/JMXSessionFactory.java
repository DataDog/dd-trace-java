package com.datadog.profiling.mlt;

import datadog.trace.profiling.Session;
import datadog.trace.profiling.SessionFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class JMXSessionFactory implements SessionFactory {
  private final Supplier<StackTraceSink> sinkSupplier;
  private final Map<Long, JMXSession> jmxSessions = new ConcurrentHashMap<>();

  public JMXSessionFactory(Supplier<StackTraceSink> sinkSupplier) {
    this.sinkSupplier = sinkSupplier;
  }

  public Session createSession(String id, Thread thread) {
    long threadId = thread.getId();
    JMXSession session =
        jmxSessions.computeIfAbsent(
            threadId, key -> new JMXSession(id, threadId, sinkSupplier, jmxSessions));
    session.incRefCount();
    return session;
  }
}
