package com.datadog.mlt.sampler;

import datadog.trace.mlt.Session;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JMXSession implements Session {
  private final String id;
  private final long threadId;
  private final Consumer<JMXSession> cleanup;
  private final ScopeStackCollector scopeStackCollector;

  public JMXSession(
      String id,
      long threadId,
      ScopeStackCollector scopeStackCollector,
      Consumer<JMXSession> cleanup) {
    this.id = id;
    this.threadId = threadId;
    this.scopeStackCollector = scopeStackCollector;
    this.cleanup = cleanup;
  }

  @Override
  public byte[] close() {
    byte[] data = scopeStackCollector.end().serialize();
    cleanup.accept(this);
    return data;
  }

  String getId() {
    return id;
  }

  long getThreadId() {
    return threadId;
  }
}
