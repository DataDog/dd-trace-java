package com.datadog.profiling.mlt;

import com.datadog.profiling.mlt.io.ConstantPool;
import com.datadog.profiling.mlt.io.FrameElement;
import com.datadog.profiling.mlt.io.FrameStack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

public final class ThreadScopeMapper {
  private final ConstantPool<FrameElement> framePool = new ConstantPool<>();
  private final ConstantPool<FrameStack> stackPool = new ConstantPool<>();
  private final ConstantPool<String> stringPool =
      new ConstantPool<>(1); // CP entry 0 will be reserved for thread name

  private final ConcurrentMap<Long, ScopeManager> collectorMap = new ConcurrentHashMap<>();

  public ScopeManager forCurrentThread() {
    return forThread(Thread.currentThread());
  }

  public ScopeManager forThread(Thread thread) {
    return forThread(thread.getId(), thread.getName());
  }

  public ScopeManager forThread(long threadId) {
    return collectorMap.get(threadId);
  }

  public ScopeManager forThread(long threadId, String threadName) {
    return forThread(
        threadId, tid -> new ScopeManager(tid, threadName, stringPool, framePool, stackPool));
  }

  private ScopeManager forThread(long threadId, Function<Long, ScopeManager> lazySupplier) {
    return collectorMap.computeIfAbsent(threadId, lazySupplier);
  }
}
