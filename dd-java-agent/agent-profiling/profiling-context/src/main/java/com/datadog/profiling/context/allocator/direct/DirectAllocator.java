package com.datadog.profiling.context.allocator.direct;

import com.datadog.profiling.context.Allocator;
import com.datadog.profiling.context.StatsDAccessor;
import com.datadog.profiling.context.allocator.AllocatedBuffer;
import datadog.trace.api.StatsDClient;
import datadog.trace.relocate.api.RatelimitedLogger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A naive implementation of a custom allocator using one big memory pool provided by a {@linkplain
 * java.nio.DirectByteBuffer}. The allocator is splitting the provided buffer into chunks of the
 * requested size and serves the allocation requests as the multiples of the chunk size.<br>
 * The free/occupied chunks are mapped in a single bitmap.<br>
 * This class is thread-safe.<br>
 * In order to improve the parallelism the implementation is using a version of striped locking when
 * there are several subsets of chunks, each guarded by a separate lock. The number of subsets is
 * set to be ~ the number of available CPU cores since there will never be more competing threads
 * than that number.<br>
 * !!! IMPORTANT: This implementation tends to degrade in situation when the pool is almost
 * exhausted. Therefore, this implementation is not suitable to be used in production as is and
 * serves mostly as the starting point for a production ready version. !!!
 */
public final class DirectAllocator implements Allocator {
  private static final Logger log = LoggerFactory.getLogger(DirectAllocator.class);
  private static final RatelimitedLogger warnlog = new RatelimitedLogger(log, 30, TimeUnit.SECONDS);

  private static final class AllocationResult {
    static final AllocationResult EMPTY = new AllocationResult(0, 0);

    final int allocatedChunks;
    final int usedChunks;

    AllocationResult(int allocatedChunks, int usedChunks) {
      this.allocatedChunks = allocatedChunks;
      this.usedChunks = usedChunks;
    }
  }

  private static final int[] MASK_ARRAY = new int[8];

  static {
    int mask = 0x80;
    for (int i = 0; i < 8; i++) {
      MASK_ARRAY[i] = mask;
      mask = mask >>> 1;
    }
  }

  private final StatsDClient statsDClient;

  private final ByteBuffer pool;
  private final ByteBuffer memorymap;
  private final int chunkSize;
  private final int numChunks;
  private final ReentrantLock[] memoryMapLocks;
  private final int memoryMapLockCount;
  private int lockSectionSize;
  private final int bitmapSize;

  private final long capacity;
  private final AtomicLong allocatedBytes = new AtomicLong(0);
  private final AtomicInteger lockSectionOffset = new AtomicInteger(0);

  public DirectAllocator(int capacity, int chunkSize) {
    log.warn(
        "DirectAllocator is an experimental implementation. It should not be used in production.");
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

    this.memoryMapLockCount = (int) Math.ceil(numChunks / (double) lockSectionSize);
    this.memoryMapLocks = new ReentrantLock[memoryMapLockCount];
    for (int i = 0; i < memoryMapLockCount; i++) {
      memoryMapLocks[i] = new ReentrantLock();
    }
    this.capacity = alignedCapacity;
    this.bitmapSize = (int) Math.ceil(lockSectionSize / 8d);
    statsDClient = StatsDAccessor.getStatsdClient();
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
    long ts = System.nanoTime();
    chunks = Math.min(chunks, numChunks);

    long delta = chunkSize * (long) chunks;
    long size = allocatedBytes.addAndGet(delta);
    boolean exhausted = false;
    while (size > capacity) {
      long overflow = size - capacity;
      long newDelta = delta - overflow;
      chunks = (int) (newDelta / chunkSize);
      size = allocatedBytes.addAndGet(chunks * (long) chunkSize - delta);
      delta = newDelta;
      exhausted = delta == 0;
    }
    if (exhausted) {
      warnlog.warn("Capacity exhausted - buffer could not be allocated");
      statsDClient.histogram("tracing.context.allocator.latency", System.nanoTime() - ts);
      return null;
    } else {
      log.trace("Allocated {} chunks, new size={} ({})", chunks, size, this);
      statsDClient.gauge("tracing.context.reserved.memory", size);
    }
    int lockSection = 0;
    int offset = 0;
    int allocated = 0;
    Chunk[] chunkArray = new Chunk[chunks];
    byte[] buffer = new byte[bitmapSize];
    ReentrantLock sectionLock;

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
      int idx;
      for (idx = 0; idx < memoryMapLocks.length; idx++) {
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
    return new DirectAllocatedBuffer(
        chunkSize * allocated, chunkSize, Arrays.copyOf(chunkArray, offset));
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
    int chunks = len;
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
          mask |= MASK_ARRAY[i];
        }
        memorymap.put(pos, (byte) ((slot & ~mask) & 0xff));
        len -= (bitStop - bitPos);
      } finally {
        lock.unlock();
      }
    }
    long size = allocatedBytes.addAndGet(-delta);
    log.trace("{} allocated chunks released - new size={} ({})", chunks, size, this);
    statsDClient.gauge("tracing.context.reserved.memory", size);
  }
}
