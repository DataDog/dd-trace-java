package com.datadog.profiling.context;

import java.nio.ByteBuffer;

public final class Allocator {
  public static final class Chunk {
    private final Allocator allocator;
    private final ByteBuffer buffer;
    private final int ref;

    Chunk(Allocator allocator, ByteBuffer buffer, int ref) {
      this.allocator = allocator;
      this.buffer = buffer;
      this.ref = ref;
    }

    public ByteBuffer getBuffer() {
      return buffer;
    }

    void release() {
      allocator.release(ref);
    }
  }

  public static final class Block {
    private final Chunk[] chunks;
    Block(Chunk ... chunks) {
      this.chunks = chunks;
    }

    public Chunk[] getChunks() {
      return chunks;
    }

    public void release() {
      for (Chunk chunk : chunks) {
        chunk.release();
      }
    }
  }

  private final ByteBuffer pool;
  private final ByteBuffer memorymap;
  private final int chunkSize;
  private final int numChunks;

  public Allocator(int capacity, int chunkSize) {
    this.chunkSize = chunkSize;
    this.numChunks = (capacity / chunkSize) + 1;
    int alignedCapacity = numChunks * chunkSize;
    this.pool = ByteBuffer.allocateDirect(alignedCapacity);
    this.memorymap = ByteBuffer.allocateDirect((numChunks / 8) + 1);
  }

  public synchronized Block allocate(int chunks) {
    if (chunks > numChunks) {
      return null;
    }
    memorymap.rewind();
    byte[] buffer = new byte[512];
    Chunk[] chunkArray = new Chunk[chunks];
    int chunkIndex = 0;
    int pos = 0;
    outer:
    while (pos < numChunks) {
      int toRead = Math.min(memorymap.remaining(), 512);
      memorymap.get(buffer, 0, toRead);
      for (int i = 0; i < toRead; i++) {
        int slot = buffer[i] & 0xff;
        if (slot == 0xff) {
          continue;
        }
        int mask = 0x80;
        int bitIndex = 0;
        while (slot != 0xff && chunkIndex < chunks) {
          if ((slot & mask) != 0) {
            bitIndex++;
            mask = mask >>> 1;
            continue;
          }
          slot |= mask;
          memorymap.put(pos + i, (byte)(slot & 0xff));
          mask = mask >>> 1;
          int ref = (pos + (i * 8) + bitIndex++);
          if (ref >= numChunks) {
            break outer;
          }
          pool.position(ref * chunkSize);
          ByteBuffer sliced = pool.slice();
          sliced.limit(chunkSize);
          chunkArray[chunkIndex++] = new Chunk(this, sliced, ref);
        }
        if (chunkIndex == chunks) {
          break outer;
        }
      }
      pos += (toRead * 8);
    }
    if (chunkIndex < chunks) {
      // unable to allocate the requested size
      // release the chunks
      for (int i = 0; i < chunkIndex; i++) {
        chunkArray[i].release();
      }
      // and return null
      return null;
    }
    return new Block(chunkArray);
  }

  void release(int ref) {
    int pos = ref / 8;
    synchronized (this) {
      int slot = memorymap.get(pos);
      int mask = ~(0x80 >>> (ref % 8));
      memorymap.put(pos, (byte)((slot & mask) & 0xff));
    }
  }
}
