package com.datadog.profiling.mlt;

import datadog.trace.profiling.Session;
import datadog.trace.profiling.SessionFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class JMXSessionFactory implements SessionFactory {
  private static final Map<Long, JMXSession> jmxSessions = new HashMap<>();

  private final StackTraceSink sink;

  public JMXSessionFactory(StackTraceSink sink) {
    this.sink = sink;
  }

  public Session createSession(Thread thread) {
    long id = thread.getId();
    JMXSession session;
    synchronized (jmxSessions) {
      session = jmxSessions.computeIfAbsent(id, this::newSession);
    }
    session.incRefCount();
    session.addThread(thread);
    return session;
  }

  private JMXSession newSession(Long key) {
    return new JMXSession(sink, JMXSessionFactory::cleanup);
  }

  private static void cleanup(Set<Long> threadIds) {
    synchronized (jmxSessions) {
      for (Long id : threadIds) {
        jmxSessions.remove(id);
      }
    }
  }
}
