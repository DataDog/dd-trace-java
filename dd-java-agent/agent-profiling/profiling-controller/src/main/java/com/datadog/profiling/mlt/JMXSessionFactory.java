package com.datadog.profiling.mlt;

import datadog.trace.api.profiling.Session;
import datadog.trace.api.profiling.SessionFactory;

public class JMXSessionFactory implements SessionFactory {
  public Session createSession() {
    return new JMXSession();
  }
}
