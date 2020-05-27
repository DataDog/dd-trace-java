package com.datadog.profiling.mlt;

import datadog.trace.profiling.Session;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JMXSession implements Session {
  private final String id;
  private final long threadId;
  private final Consumer<JMXSession> cleanup;

  public JMXSession(String id, long threadId, Consumer<JMXSession> cleanup) {
    this.id = id;
    this.threadId = threadId;
    this.cleanup = cleanup;
  }

  @Override
  public void close() {
    cleanup.accept(this);
  }

  String getId() {
    return id;
  }

  long getThreadId() {
    return threadId;
  }
}
