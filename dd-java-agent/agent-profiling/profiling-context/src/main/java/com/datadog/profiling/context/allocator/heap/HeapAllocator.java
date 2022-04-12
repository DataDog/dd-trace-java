package com.datadog.profiling.context.allocator.heap;

import com.datadog.profiling.context.Allocator;
import com.datadog.profiling.context.allocator.AllocatedBuffer;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.StatsDClient;
import datadog.trace.api.Tracer;
import datadog.trace.relocate.api.RatelimitedLogger;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A straight-forward {@linkplain Allocator} implementation which satisfies the allocation requests
 * by instantiating {@linkplain java.nio.HeapByteBuffer} instances.
 */
public final class HeapAllocator implements Allocator {
  private static final Logger log = LoggerFactory.getLogger(HeapAllocator.class);
  private static final RatelimitedLogger warnlog = new RatelimitedLogger(log, 30, TimeUnit.SECONDS);

  private final int chunkSize;
  private final AtomicLong remaining;
  private final long topMemory;

  private final StatsDClient statsDClient;

  public HeapAllocator(int capacity, int chunkSize) {
    this.chunkSize = chunkSize;
    int numChunks = (int) Math.ceil(capacity / (double) chunkSize);
    this.topMemory = numChunks * (long) chunkSize;
    this.remaining = new AtomicLong(topMemory);

    StatsDClient statsd = getStatsdClient();
    statsDClient = statsd;
  }

  private static StatsDClient getStatsdClient() {
    try {
      Tracer tracer = GlobalTracer.get();
      Field fld = tracer.getClass().getDeclaredField("statsDClient");
      fld.setAccessible(true);
      StatsDClient statsd = (StatsDClient) fld.get(tracer);
      log.debug("Set up custom StatsD Client instance {}", statsd);
      return statsd;
    } catch (Throwable t) {
      if (log.isDebugEnabled()) {
        log.warn("Unable to obtain a StatsD client instance", t);
      } else {
        log.warn("Unable to obtain a StatsD client instance");
      }
      return StatsDClient.NO_OP;
    }
  }

  @Override
  public int getChunkSize() {
    return chunkSize;
  }

  @Override
  public AllocatedBuffer allocate(int size) {
    long ts = System.nanoTime();
    long newRemaining = 0;
    try {
      // align at chunkSize
      size = (((size - 1) / chunkSize) + 1) * chunkSize;
      newRemaining = remaining.addAndGet(-size);
      while (newRemaining < 0) {
        long restored = remaining.addAndGet(size); // restore the remaining size
        size = (int) (newRemaining + size);
        if (size <= chunkSize) {
          warnlog.warn("Capacity exhausted - buffer could not be allocated");
          return null;
        }
        // align at chunkSize
        size = (((size - 1) / chunkSize) + 1) * chunkSize;
        newRemaining = remaining.addAndGet(-size);
      }
      statsDClient.gauge("tracing.context.reserved.memory", topMemory - newRemaining);
      return new HeapAllocatedBuffer(this, size);
    } finally {
      long timeDelta = System.nanoTime() - ts;
      statsDClient.histogram("tracing.context.allocator.latency", timeDelta);
    }
  }

  @Override
  public AllocatedBuffer allocateChunks(int chunks) {
    return allocate(chunks * chunkSize);
  }

  void release(int size) {
    remaining.addAndGet(size);
  }
}
