package com.datadog.mlt.sampler;

import com.datadog.mlt.io.ConstantPool;
import com.datadog.mlt.io.FrameElement;
import com.datadog.mlt.io.FrameSequence;
import com.datadog.mlt.io.IMLTChunk;
import java.util.ArrayDeque;
import java.util.Deque;

public final class ScopeManager {
  private final ConstantPool<FrameElement> framePool;
  private final ConstantPool<FrameSequence> stackPool;
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
      ConstantPool<FrameSequence> stackPool) {
    this.stringPool = stringPool;
    this.framePool = framePool;
    this.stackPool = stackPool;
    this.threadId = threadId;
    this.threadName = threadName;
  }

  public ScopeStackCollector startScope(String scopeId) {
    ScopeStackCollector scopeStackCollector =
        new ScopeStackCollector(
            scopeId,
            this,
            System.nanoTime(),
            System.currentTimeMillis(),
            stringPool,
            framePool,
            stackPool);
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

  IMLTChunk endScope(ScopeStackCollector target) {
    ScopeStackCollector scopeStackCollector = scopeCollectorQueue.removeLast();
    while (scopeStackCollector != null && !target.equals(scopeStackCollector)) {
      scopeCollectorQueue.removeLast();
    }
    current =
        scopeCollectorQueue.isEmpty()
            ? null
            : scopeCollectorQueue.getLast(); // previous scope published (volatile)
    if (scopeStackCollector == null) {
      // TODO warning
    }
    return target;
  }

  long getThreadId() {
    return threadId;
  }

  String getThreadName() {
    return threadName;
  }
}
