package com.datadog.mlt.sampler;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.trace.core.util.ThreadStackAccess;
import datadog.trace.mlt.Session;
import org.junit.jupiter.api.Test;

public class JMXSessionTest {

  @Test
  public void createSession() throws InterruptedException {
    ThreadStackAccess.enableJmx();
    JMXSessionFactory sessionFactory = new JMXSessionFactory();

    Session session = sessionFactory.createSession("id", Thread.currentThread());
    try {
      assertNotNull(session);
      Thread.sleep(100);
    } finally {
      assertNotNull(session.close());
    }
  }
}
