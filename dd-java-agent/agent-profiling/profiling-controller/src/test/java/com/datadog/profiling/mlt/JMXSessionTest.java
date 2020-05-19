package com.datadog.profiling.mlt;

import datadog.trace.core.util.ThreadStackAccess;
import datadog.trace.profiling.Session;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import java.lang.management.ThreadInfo;
import java.util.concurrent.atomic.AtomicBoolean;

public class JMXSessionTest {

  @Test
  public void createSession() throws InterruptedException {
    ThreadStackAccess.enableJmx();
    Object lock = new Object();
    AtomicBoolean asserted = new AtomicBoolean();
    JMXSessionFactory sessionFactory =
        new JMXSessionFactory(() -> new AssertStackTraceSink(asserted, lock));
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
        new JMXSessionFactory(() -> new AssertStackTraceSink(asserted, lock));
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
    // TODO
    /*
    ThreadStackAccess.enableJmx();
    Object lock = new Object();
    AtomicBoolean asserted = new AtomicBoolean();
    JMXSessionFactory sessionFactory = new JMXSessionFactory(JFRStackTraceSink::new);
    try (Session session = sessionFactory.createSession("id", Thread.currentThread())) {
      Thread.sleep(100);
    }
    Assert.assertTrue(jfrFile.toFile().exists());
    Assert.assertTrue(jfrFile.toFile().length() > 0);

     */
  }

  private static class AssertStackTraceSink implements StackTraceSink {
    private final AtomicBoolean asserted;
    private final Object lock;

    public AssertStackTraceSink(AtomicBoolean asserted, Object lock) {
      this.asserted = asserted;
      this.lock = lock;
    }

    @Override
    public void write(String id, ThreadInfo[] threadInfos) {
      Assert.assertTrue(threadInfos.length > 0);
      synchronized (lock) {
        asserted.set(true);
        lock.notifyAll();
      }

    }

    @Override
    public byte[] flush() {
      return new byte[0];
    }
  }
}
