package com.datadog.profiling.context.allocator.direct;

import com.datadog.profiling.context.Allocator;
import com.datadog.profiling.context.allocator.AllocatedBuffer;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.StatsDClient;
import datadog.trace.api.Tracer;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DirectAllocator implements Allocator {
  private static final Logger log = LoggerFactory.getLogger(DirectAllocator.class);

  private static final class AllocationResult {
    static final AllocationResult EMPTY = new AllocationResult(0, 0);

    final int allocatedChunks;
    final int usedChunks;

    AllocationResult(int allocatedChunks, int usedChunks) {
      this.allocatedChunks = allocatedChunks;
      this.usedChunks = usedChunks;
    }
  }

  private final StatsDClient statsDClient;

  private final ByteBuffer pool;
  private final ByteBuffer memorymap;
  private final int chunkSize;
  private final int numChunks;
  private final ReentrantLock[] memoryMapLocks;
  private int lockSectionSize;
  private final int bitmapSize;

  private final long capacity;
  private final AtomicLong allocatedBytes = new AtomicLong(0);
  private final AtomicInteger lockSectionOffset = new AtomicInteger(0);

  public DirectAllocator(int capacity, int chunkSize) {
    int cpus = Runtime.getRuntime().availableProcessors();

    int targetNumChunks = (int) Math.ceil(capacity / (double) chunkSize);
    this.lockSectionSize = (int) Math.ceil((targetNumChunks / (double) (cpus * 8))) * 8;
    this.numChunks =
        (int) (Math.ceil(targetNumChunks / (double) lockSectionSize) * lockSectionSize);
    chunkSize = (int) Math.ceil(((double) capacity / (numChunks * chunkSize)) * chunkSize);
    chunkSize = (int) Math.ceil(chunkSize / 8d) * 8;
    int alignedCapacity = numChunks * chunkSize;
    this.chunkSize = chunkSize;
    this.pool = ByteBuffer.allocateDirect(alignedCapacity);
    this.memorymap = ByteBuffer.allocateDirect((int) Math.ceil(numChunks / 8d));

    this.memoryMapLocks = new ReentrantLock[numChunks / lockSectionSize];
    for (int i = 0; i < memoryMapLocks.length; i++) {
      memoryMapLocks[i] = new ReentrantLock();
    }
    this.capacity = alignedCapacity;
    this.bitmapSize = lockSectionSize / 8;
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
  public AllocatedBuffer allocate(int bytes) {
    return allocateChunks((int) Math.ceil(bytes / (double) chunkSize));
  }

  @Override
  public AllocatedBuffer allocateChunks(int chunks) {
    chunks = Math.min(chunks, numChunks);

    long delta = chunkSize * (long) chunks;
    long size = allocatedBytes.addAndGet(delta);
    if (size > capacity) {
      long overflow = size - capacity;
      long newDelta = delta - overflow;
      chunks = (int) (newDelta / chunkSize);
      size = allocatedBytes.addAndGet(chunks * (long) chunkSize - delta);
    }
    if (size == 0) {
      log.warn("Capacity exhausted - buffer could not be allocated");
      statsDClient.gauge("tracing.context.reserved.memory", capacity);
      return null;
    } else {
      statsDClient.gauge("tracing.context.reserved.memory", size);
    }
    int lockSection = 0;
    int offset = 0;
    int allocated = 0;
    Chunk[] chunkArray = new Chunk[chunks];
    byte[] buffer = new byte[bitmapSize];
    ReentrantLock sectionLock;
    long ts = System.nanoTime();

    while (allocated < chunks) {
      int offsetValue = 0;
      int newOffsetValue = 0;
      do {
        offsetValue = lockSectionOffset.get();
        newOffsetValue =
            (offsetValue + 104729)
                % memoryMapLocks
                    .length; // simple hashing using 10000th prime number and mod operation
      } while (!lockSectionOffset.compareAndSet(offsetValue, newOffsetValue));

      for (int idx = 0; idx < memoryMapLocks.length; idx++) {
        lockSection = offsetValue % memoryMapLocks.length;
        sectionLock = null;
        try {
          memoryMapLocks[lockSection].lock();
          sectionLock = memoryMapLocks[lockSection];

          int memorymapOffset = lockSection * bitmapSize;
          for (int i = 0; i < bitmapSize; i++) {
            buffer[i] = memorymap.get(memorymapOffset + i);
          }
          AllocationResult rslt =
              allocateChunks(buffer, lockSection, chunkArray, chunks - allocated, offset);
          offset += rslt.usedChunks;
          allocated += rslt.allocatedChunks;
          if (allocated == chunks) {
            break;
          }
        } finally {
          if (sectionLock != null) {
            sectionLock.unlock();
          }
        }
      }
    }
    statsDClient.histogram("tracing.context.allocator.latency", System.nanoTime() - ts);
    return new DirectAllocatedBuffer(chunkSize * allocated, chunkSize, Arrays.copyOf(chunkArray, offset));
  }

  private AllocationResult allocateChunks(
      byte[] bitmap, int lockSection, Chunk[] chunks, int toAllocate, int offset) {
    int allocated = 0;
    int chunkCounter = 0;
    int overlay = 0x00;
    for (int index = 0; index < bitmap.length; index++) {
      int slot = bitmap[index] & 0xff;
      if (slot == 0xff) {
        overlay = 0;
        continue;
      }
      if ((overlay & 0x01) != 0) {
        overlay = 0x01 << 8;
      } else {
        overlay = 0;
      }
      int mask = 0x80;
      int bitIndex = 0;
      while (slot != 0xff && allocated < toAllocate) {
        if ((slot & mask) != 0) {
          bitIndex++;
          mask = mask >>> 1;
          continue;
        }
        int previousMask = mask;
        int lockOffset = lockSection * (lockSectionSize / 8);
        int slotOffset = lockOffset + index;
        slot |= mask;
        memorymap.put(slotOffset, (byte) (slot & 0xff));

        allocated++;
        overlay |= mask;
        mask = mask >>> 1;

        int ref = (slotOffset * 8 + bitIndex++);
        if (chunkCounter > 0) {
          Chunk previous = chunks[chunkCounter + offset - 1];
          if ((overlay & (previousMask << 1)) != 0) {
            // can create a contiguous chunk
            previous.extend();
            continue;
          }
        }
        pool.position(ref * chunkSize);
        ByteBuffer sliced = pool.slice();
        sliced.limit(chunkSize);
        chunks[chunkCounter++ + offset] = new Chunk(this, sliced, ref);
      }
    }
    return allocated == 0 ? AllocationResult.EMPTY : new AllocationResult(allocated, chunkCounter);
  }

  void release(int ref, int len) {
    long delta = chunkSize * (long) len;
    int offset = 0;
    while (offset < len) {
      int pos = (ref + offset) / 8;
      int lockIndex = pos / lockSectionSize;
      ReentrantLock lock = memoryMapLocks[lockIndex];
      try {
        lock.lock();
        int slot = memorymap.get(pos);
        int mask = 0;
        int bitPos = (ref + offset) % 8;
        int bitStop = Math.min(bitPos + len, 8);
        for (int i = bitPos; i < bitStop; i++) {
          mask |= (0x80 >>> i);
        }
        memorymap.put(pos, (byte) ((slot & ~mask) & 0xff));
        len -= (bitStop - bitPos);
      } finally {
        lock.unlock();
      }
    }
    long newVal = allocatedBytes.addAndGet(-delta);
  }
}
