package com.datadog.profiling.mlt;

import datadog.trace.core.util.ThreadStackAccess;
import datadog.trace.profiling.Session;
import java.lang.management.ThreadInfo;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class JMXSessionTest {

  @Test
  public void createSession() throws InterruptedException {
    ThreadStackAccess.enableJmx();
    Object lock = new Object();
    AtomicBoolean asserted = new AtomicBoolean();
    JMXSessionFactory sessionFactory =
        new JMXSessionFactory(new AssertStackTraceSink(asserted, lock));
    try (Session session = sessionFactory.createSession("id", Thread.currentThread())) {
      synchronized (lock) {
        lock.wait(2000);
      }
    }
    Assert.assertTrue(asserted.get());
  }

  @Test
  public void createJFRSession() throws InterruptedException {
    ThreadStackAccess.enableJmx();
    Object lock = new Object();
    AtomicBoolean asserted = new AtomicBoolean();
    StackTraceSink sink = new JFRStackTraceSink();
    JMXSessionFactory sessionFactory = new JMXSessionFactory(sink);
    try (Session session = sessionFactory.createSession("id", Thread.currentThread())) {
      Thread.sleep(100);
    }
    byte[] buffer = sink.flush();
    Assert.assertNotNull(buffer);
    Assert.assertTrue(buffer.length > 0);
  }

  private static class AssertStackTraceSink implements StackTraceSink {
    private final AtomicBoolean asserted;
    private final Object lock;

    public AssertStackTraceSink(AtomicBoolean asserted, Object lock) {
      this.asserted = asserted;
      this.lock = lock;
    }

    @Override
    public void write(String[] id, ThreadInfo[] threadInfos) {
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
