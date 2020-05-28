package com.datadog.profiling.mlt;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ThreadStackCollector {
  private final ConstantPool<FrameElement> framePool;
  private final ConstantPool<StackElement> stackPool;
  private final ConstantPool<String> stringPool;

  private final Deque<ScopeStackCollector> scopeCollectorQueue = new ArrayDeque<>();

  private final long threadId;

  public ThreadStackCollector(long threadId, String threadName, ConstantPool<String> stringPool, ConstantPool<FrameElement> framePool, ConstantPool<StackElement> stackPool) {
    this.stringPool = stringPool;
    this.framePool = framePool;
    this.stackPool = stackPool;
    this.threadId = threadId;
    this.stringPool.get(threadName); // insert threadName constant at index 0 in the constant pool
  }

  public ScopeStackCollector startScope(String scopeId) {
    ScopeStackCollector scopeStackCollector = new ScopeStackCollector(scopeId, this, System.nanoTime(), stringPool, framePool, stackPool);
    scopeCollectorQueue.addLast(scopeStackCollector);
    return scopeStackCollector;
  }

  byte[] endScope(ScopeStackCollector target) {
    ScopeStackCollector scopeStackCollector = scopeCollectorQueue.removeLast();
    while (scopeStackCollector != null && !target.equals(scopeStackCollector)) {
      scopeCollectorQueue.removeLast();
    }
    if (scopeStackCollector == null) {
      // TODO warning
    }
    return target.serialize();
  }

  long getThreadId() {
    return threadId;
  }

  String getThreadName() {
    return stringPool.get(0);
  }
}
