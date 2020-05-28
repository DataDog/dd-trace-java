package com.datadog.profiling.mlt;

import datadog.trace.profiling.Session;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JMXSession implements Session {
  private final String id;
  private final long threadId;
  private final Consumer<JMXSession> cleanup;
  private final ScopeStackCollector scopeStackCollector;
  private final AtomicInteger refCount = new AtomicInteger();

  public JMXSession(String id, long threadId, ScopeStackCollector scopeStackCollector, Consumer<JMXSession> cleanup) {
    this.id = id;
    this.threadId = threadId;
    this.scopeStackCollector = scopeStackCollector;
    this.cleanup = cleanup;
  }

  @Override
  public void close() {
    scopeStackCollector.end();
    cleanup.accept(this);
  }

  String getId() {
    return id;
  }

  long getThreadId() {
    return threadId;
  }

  void activate() {
    refCount.getAndIncrement();
  }

  int deactivate() {
    return refCount.decrementAndGet();
  }
}
