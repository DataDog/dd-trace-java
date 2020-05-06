package com.datadog.profiling.mlt;

import datadog.trace.profiling.Session;
import datadog.trace.profiling.SessionFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class JMXSessionFactory implements SessionFactory {
  private static final AtomicInteger refCount = new AtomicInteger();
  private static final AtomicReference<JMXSession> currentSession = new AtomicReference<>(null);

  public Session createSession(Thread thread) {
    int prevCount = refCount.getAndIncrement();
    if (prevCount == 0) {
      currentSession.compareAndSet(null, new JMXSession(this, thread));
    }
    Session session = currentSession.get();
    session.addThread(thread);
    return session;
  }

  void decCount() {
    int currentCount = refCount.decrementAndGet();
    if (currentCount == 0) {
      currentSession.set(null);
    }
  }
}
