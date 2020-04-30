package com.datadog.profiling.mlt;

import datadog.trace.api.profiling.Session;

public class JMXSession implements Session {
  private final JMXSessionFactory factory;

  public JMXSession(JMXSessionFactory factory) {
    this.factory = factory;
  }

  public void close() {
    factory.decCount();
  }
}
