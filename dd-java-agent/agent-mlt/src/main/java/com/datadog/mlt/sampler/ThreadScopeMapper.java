package com.datadog.mlt.sampler;

import com.datadog.mlt.io.ConstantPool;
import com.datadog.mlt.io.FrameElement;
import com.datadog.mlt.io.FrameSequence;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

public final class ThreadScopeMapper {
  private final ConstantPool<FrameElement> framePool = new ConstantPool<>();
  private final ConstantPool<FrameSequence> stackPool = new ConstantPool<>();
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
