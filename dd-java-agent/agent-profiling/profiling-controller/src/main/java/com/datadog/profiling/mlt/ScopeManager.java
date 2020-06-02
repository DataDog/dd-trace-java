package com.datadog.profiling.mlt;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ScopeManager {
  private final ConstantPool<FrameElement> framePool;
  private final ConstantPool<StackElement> stackPool;
  private final ConstantPool<String> stringPool;
  private volatile ScopeStackCollector current;

  private final Deque<ScopeStackCollector> scopeCollectorQueue = new ArrayDeque<>();

  private final long threadId;
  private final String threadName;

  public ScopeManager(
      long threadId,
      String threadName,
      ConstantPool<String> stringPool,
      ConstantPool<FrameElement> framePool,
      ConstantPool<StackElement> stackPool) {
    this.stringPool = stringPool;
    this.framePool = framePool;
    this.stackPool = stackPool;
    this.threadId = threadId;
    this.threadName = threadName;
  }

  public ScopeStackCollector startScope(String scopeId) {
    ScopeStackCollector scopeStackCollector =
        new ScopeStackCollector(scopeId, this, System.nanoTime(), stringPool, framePool, stackPool);
    scopeCollectorQueue.addLast(scopeStackCollector);
    current = scopeStackCollector; // published (volatile) as current
    return scopeStackCollector;
  }

  /*
   * Called by the sampler thread
   */
  public ScopeStackCollector getCurrentScope() {
    return current;
  }

  byte[] endScope(ScopeStackCollector target) {
    ScopeStackCollector scopeStackCollector = scopeCollectorQueue.removeLast();
    while (scopeStackCollector != null && !target.equals(scopeStackCollector)) {
      scopeCollectorQueue.removeLast();
    }
    current = scopeCollectorQueue.isEmpty() ? null : scopeCollectorQueue.getLast(); // previous scope published (volatile)
    if (scopeStackCollector == null) {
      // TODO warning
    }
    return target.serialize();
  }

  long getThreadId() {
    return threadId;
  }

  String getThreadName() {
    return threadName;
  }
}
