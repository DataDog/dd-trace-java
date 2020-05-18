package com.datadog.profiling.mlt;

import datadog.trace.core.util.ThreadStackAccess;
import datadog.trace.profiling.Session;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;

public class JMXSessionTest {

  @Test
  public void createSession() throws InterruptedException {
    ThreadStackAccess.enableJmx();
    Object lock = new Object();
    AtomicBoolean asserted = new AtomicBoolean();
    JMXSessionFactory sessionFactory =
        new JMXSessionFactory(
            (id, stackTraces) -> {
              Assert.assertFalse(stackTraces.isEmpty());
              synchronized (lock) {
                asserted.set(true);
                lock.notifyAll();
              }
            });
    try (Session session = sessionFactory.createSession("id", Thread.currentThread())) {
      synchronized (lock) {
        lock.wait(2000);
      }
    }
    Assert.assertTrue(asserted.get());
  }

  @Test
  public void createNestedSession() throws InterruptedException {
    ThreadStackAccess.enableJmx();
    Object lock = new Object();
    AtomicBoolean asserted = new AtomicBoolean();
    JMXSessionFactory sessionFactory =
        new JMXSessionFactory(
            (id, stackTraces) -> {
              Assert.assertFalse(stackTraces.isEmpty());
              synchronized (lock) {
                asserted.set(true);
                lock.notifyAll();
              }
            });
    try (Session session = sessionFactory.createSession("id", Thread.currentThread())) {
      try (Session session2 = sessionFactory.createSession("id", Thread.currentThread())) {
        Assert.assertSame(session, session2);
        synchronized (lock) {
          lock.wait(2000);
        }
      }
    }
    Assert.assertTrue(asserted.get());
  }

  @Test
  public void createJFRSession() throws InterruptedException {
    ThreadStackAccess.enableJmx();
    Object lock = new Object();
    AtomicBoolean asserted = new AtomicBoolean();
    JMXSessionFactory sessionFactory =
      new JMXSessionFactory(new JFRStackTraceSink());
    try (Session session = sessionFactory.createSession("id", Thread.currentThread())) {
    }
    Assert.assertTrue(asserted.get());
  }
}
