package com.datadog.profiling.mlt;

import datadog.trace.api.profiling.Session;
import datadog.trace.api.profiling.SessionFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class JMXSessionFactory implements SessionFactory {
  private static final AtomicInteger refCount = new AtomicInteger();
  private static final AtomicReference<JMXSession> currentSession = new AtomicReference<>(null);

  public Session createSession() {
    int prevCount = refCount.getAndIncrement();
    if (prevCount == 0) {
      currentSession.compareAndSet(null, new JMXSession(this));
    }
    return currentSession.get();
  }

  void decCount() {
    int currentCount = refCount.decrementAndGet();
    if (currentCount == 0) {
      currentSession.set(null);
    }
  }
}
