package com.datadog.profiling.mlt;

import datadog.trace.api.profiling.Session;

public class JMXSession implements Session {

  public JMXSession() {
    // TODO init ref counting
  }

  public void stop() {
    // TODO handle ref-counting
  }
}
