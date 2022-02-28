package com.datadog.profiling.context.allocator.heap;

import com.datadog.profiling.context.Allocator;
import com.datadog.profiling.context.allocator.AllocatedBuffer;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.StatsDClient;
import datadog.trace.api.Tracer;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HeapAllocator implements Allocator {
  private static final Logger log = LoggerFactory.getLogger(HeapAllocator.class);

  private final int chunkSize;
  private final AtomicLong remaining;
  private final long topMemory;

  private final StatsDClient statsDClient;

  public HeapAllocator(int capacity, int chunkSize) {
    this.chunkSize = chunkSize;
    int numChunks = (int) Math.ceil(capacity / (double) chunkSize);
    this.topMemory = numChunks * (long) chunkSize;
    this.remaining = new AtomicLong(topMemory);

    StatsDClient statsd = StatsDClient.NO_OP;
    try {
      Tracer tracer = GlobalTracer.get();
      Field fld = tracer.getClass().getDeclaredField("statsDClient");
      fld.setAccessible(true);
      statsd = (StatsDClient) fld.get(tracer);
      log.info("Set up custom StatsD Client instance {}", statsd);
    } catch (Throwable t) {
      t.printStackTrace();
    }
    statsDClient = statsd;
  }

  @Override
  public int getChunkSize() {
    return chunkSize;
  }

  @Override
  public AllocatedBuffer allocate(int capacity) {
    long newRemaining = remaining.addAndGet(-capacity);
    if (newRemaining >= 0) {
      statsDClient.gauge("tracing.context.reserved.memory", topMemory - newRemaining);
      return new HeapAllocatedBuffer(this, capacity);
    } else {
      // no allocation happened; return the capacity back to the pool
      remaining.addAndGet(capacity);
      statsDClient.gauge("tracing.context.reserved.memory", topMemory - newRemaining - capacity);
    }
    return null;
  }

  @Override
  public AllocatedBuffer allocateChunks(int chunks) {
    return allocate(chunks * chunkSize);
  }

  void release(int size) {
    remaining.addAndGet(size);
  }
}
