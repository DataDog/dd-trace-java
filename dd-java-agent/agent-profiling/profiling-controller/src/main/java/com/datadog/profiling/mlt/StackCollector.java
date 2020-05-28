package com.datadog.profiling.mlt;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

// TODO should be moved to JMXSampler
public final class StackCollector {
  private final ConstantPool<FrameElement> framePool = new ConstantPool<>();
  private final ConstantPool<StackElement> stackPool = new ConstantPool<>();
  private final ConstantPool<String> stringPool = new ConstantPool<>(1); // CP entry 0 will be reserved for thread name

  private final ConcurrentMap<Long, ThreadStackCollector> collectorMap = new ConcurrentHashMap<>();

  public ThreadStackCollector forCurrentThread() {
    return forThread(Thread.currentThread());
  }

  public ThreadStackCollector forThread(Thread thread) {
    return forThread(thread.getId(), thread.getName());
  }

  public ThreadStackCollector forThread(long threadId) {
    return collectorMap.get(threadId);
  }

  public ThreadStackCollector forThread(long threadId, String threadName) {
    return forThread(threadId, tid -> new ThreadStackCollector(tid, threadName, stringPool, framePool, stackPool));
  }

  private ThreadStackCollector forThread(long threadId, Function<Long, ThreadStackCollector> lazySupplier) {
    return collectorMap.computeIfAbsent(threadId, lazySupplier);
  }

}
