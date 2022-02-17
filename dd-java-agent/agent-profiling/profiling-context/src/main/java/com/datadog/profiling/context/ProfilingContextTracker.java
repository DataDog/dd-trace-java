package com.datadog.profiling.context;

import datadog.trace.api.profiling.ContextTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ProfilingContextTracker implements ContextTracker {
  private static final Logger log = LoggerFactory.getLogger(ProfilingContextTracker.class);

  private static final long TRANSITION_MASK = 0xC000000000000000L;
  private static final long TIMESTAMP_MASK = ~TRANSITION_MASK;

  private static final MethodHandle TIMESTAMP_MH;

  static {
    MethodHandle mh = null;
    try {
      Class<?> clz = ProfilingContextTracker.class.getClassLoader().loadClass("jdk.jfr.internal.JVM");
      mh = MethodHandles.lookup().findStatic(clz, "counterTime", MethodType.methodType(long.class));
    } catch (Throwable t) {
      log.error("Failed to initialize JFR timestamp access");
    }
    TIMESTAMP_MH = mh;
  }

  private static long timestamp() {
    try {
      return (long)TIMESTAMP_MH.invokeExact();
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  private final ConcurrentMap<Long, LongSequence> threadSequences = new ConcurrentHashMap<>(64);
  private final ThreadLocal<LongSequence> localThreadBuffer = ThreadLocal.withInitial(this::initThreadBuffer);
  private final long timestamp;
  private final Allocator allocator;

  public ProfilingContextTracker(Allocator allocator) {
    this.timestamp = timestamp();
    this.allocator = allocator;
  }

  private LongSequence initThreadBuffer() {
    return threadSequences.computeIfAbsent(Thread.currentThread().getId(), k -> new LongSequence(allocator));
  }


  @Override
  public void activateContext() {
    if (TIMESTAMP_MH != null) {
      long ts = timestamp() - timestamp;
      store(ts & TIMESTAMP_MASK);
//      log.info("activated[{}]", ts);
    }
  }

  @Override
  public void deactivateContext(boolean maybe) {
    if (TIMESTAMP_MH != null) {
      long ts = timestamp() - timestamp;
      store((ts & TIMESTAMP_MASK) | (maybe ? 0x4000000000000000L : 0x8000000000000000L));
//      log.info("deactivated[{}]: {}", ts, maybe);
    }
  }

  @Override
  public void persist() {
    int size = threadSequences.values().stream().mapToInt(v -> {
      synchronized(v) {
        int s = v.size();
//        v.release();
        return s;
      }
    }).sum();
    log.info("span transition data: {}", size * 8);
  }

  @Override
  public void release() {
    threadSequences.values().forEach(LongSequence::release);
  }

  private void store(long value) {
    LongSequence sequence = localThreadBuffer.get();
    boolean added = true;
    synchronized (sequence) {
      added = sequence.add(value);
    }
    if (!added) {
      log.warn("Profiling Context Buffer is full. Losing data.");
    }
  }
}
