package datadog.trace.util;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A write buffer built from fixed-size chunks rather than one large array, for serializing payloads
 * of up to a few MiB without a large contiguous allocation.
 *
 * <p><b>Why chunks.</b> A multi-MiB {@code byte[]} is a large object on every region-based
 * collector: G1 treats anything over half a region as humongous (1 MB regions on heaps up to about
 * 2 GB), Shenandoah anything over a region, and ZGC moves anything over 256 KiB off its small
 * pages. Humongous objects sit in old gen, are never compacted, and push the collector toward
 * concurrent cycles. Each chunk here is an array of {@link #CHUNK_SIZE} bytes, a whole object of at
 * most 256 KiB for any array header layout, which is regular-sized on all of them.
 *
 * <p><b>Messages, marks and rewinding.</b> Callers write a message, then call {@link #mark()} to
 * complete it. {@link #rewind()} drops everything written since the last mark, so a message that
 * fails part-way never leaves partial bytes behind. Only completed messages are visible through
 * {@link #completedChunks()}.
 *
 * <p><b>Running out of room never throws.</b> Once a write would exceed {@link #capacity()}, the
 * buffer fails: that write and every later one is discarded, and the next {@link #mark()} rewinds
 * the failed message and returns {@code false}. A caller checks one result per message instead of
 * handling an exception from any put, and the fast path stays a single bounds check because a
 * failed buffer just points writes at a small scratch array.
 *
 * <p><b>Primitives never straddle chunks.</b> A typed put that doesn't fit in the rest of the
 * current chunk starts the next one, leaving at most 7 unused bytes. Each chunk is only read up to
 * its fill point, so the completed bytes are still one contiguous stream. Byte arrays are split
 * across chunks.
 *
 * <p><b>The capacity is exact.</b> It counts data bytes: unused chunk tails don't count against it,
 * so the buffer holds exactly as much as a single array of the same capacity would. The last chunk
 * is only as long as the remaining capacity, so a small buffer is a single small chunk.
 *
 * <p><b>Retention.</b> Chunks are allocated on first use, and {@link #reset()} keeps the chunks
 * that held completed data, dropping the rest. Steady traffic reuses the same chunks; after a burst
 * the extra chunks are released at the next reset. Nothing is allocated before the first write.
 *
 * <p>Not thread-safe: a buffer is written and read by one thread at a time.
 */
public class ChunkedBuffer {

  /**
   * Usable bytes per chunk: 256 KiB less 32, so the whole array object, header included, is at most
   * 256 KiB with 12, 16 or 24 byte array headers. 256 KiB is exactly at ZGC's small-object limit
   * and at Shenandoah's humongous threshold for its minimum region size; both allow an object of
   * exactly that size.
   */
  public static final int CHUNK_SIZE = 256 * 1024 - 32;

  /** Where writes go once the buffer has failed; reused from the start whenever it fills. */
  private static final int SCRATCH_SIZE = 64;

  private final int maxBytes;
  private final int maxChunks;
  private final byte[][] chunks;
  private final ByteBuffer[] views;
  private final int[] fills;
  // the data offset each chunk starts at, and how far into it writes may go, set on entering it
  private final int[] starts;
  private final int[] limits;
  private int allocatedChunks;

  // the chunk being written: current is -1 and limit 0 until the first write
  private int current = -1;
  private byte[] bytes;
  private ByteBuffer view;
  private int pos;
  private int limit;

  private int markChunk = -1;
  private int markPos;
  private int messageCount;

  private boolean failed;
  private byte[] scratchBytes;
  private ByteBuffer scratchView;

  /**
   * @param maxBytes the most data the buffer may hold
   */
  public ChunkedBuffer(int maxBytes) {
    this.maxBytes = Math.max(0, maxBytes);
    // each chunk may leave up to 7 bytes unused, so allow for one more chunk than whole chunks
    this.maxChunks = this.maxBytes == 0 ? 0 : this.maxBytes / (CHUNK_SIZE - 7) + 1;
    this.chunks = new byte[maxChunks][];
    this.views = new ByteBuffer[maxChunks];
    this.fills = new int[maxChunks];
    this.starts = new int[maxChunks];
    this.limits = new int[maxChunks];
  }

  /** The most bytes this buffer can hold. */
  public final int capacity() {
    return maxBytes;
  }

  /** Whether the buffer holds any completed messages. */
  public final boolean isDirty() {
    return messageCount > 0;
  }

  /** The number of completed, non-empty messages. */
  public final int messageCount() {
    return messageCount;
  }

  /**
   * Completes the message written since the last mark.
   *
   * @return {@code true} if the message was kept; {@code false} if it didn't fit, in which case it
   *     has been rewound and the buffer accepts writes again
   */
  public final boolean mark() {
    if (failed) {
      rewind();
      return false;
    }
    if (current != markChunk || pos != markPos) {
      // count only non-empty messages
      ++messageCount;
      markChunk = current;
      markPos = pos;
    }
    return true;
  }

  /** Discards everything written since the last {@link #mark()}, keeping completed messages. */
  public final void rewind() {
    failed = false;
    moveTo(markChunk);
    pos = markPos;
  }

  /**
   * Discards all messages and releases the chunks the completed messages didn't need, keeping at
   * least one once any has been allocated.
   */
  public void reset() {
    int keep = Math.max(1, markChunk + 1);
    for (int i = keep; i < allocatedChunks; ++i) {
      chunks[i] = null;
      views[i] = null;
    }
    allocatedChunks = Math.min(allocatedChunks, keep);
    failed = false;
    markChunk = -1;
    markPos = 0;
    messageCount = 0;
    moveTo(-1);
    pos = 0;
  }

  /**
   * Returns views of the completed messages' bytes, one per chunk in write order. Each call returns
   * new views, so reading them doesn't affect the buffer or later calls. The views share the
   * buffer's chunks: they're valid until the next write, rewind or reset.
   */
  public final List<ByteBuffer> completedChunks() {
    if (markChunk < 0) {
      return Collections.emptyList();
    }
    List<ByteBuffer> completed = new ArrayList<>(markChunk + 1);
    for (int i = 0; i < markChunk; ++i) {
      if (fills[i] > 0) {
        completed.add(ByteBuffer.wrap(chunks[i], 0, fills[i]));
      }
    }
    if (markPos > 0) {
      completed.add(ByteBuffer.wrap(chunks[markChunk], 0, markPos));
    }
    return completed;
  }

  /** The number of bytes in the completed messages. */
  public final int completedBytes() {
    return markChunk < 0 ? 0 : starts[markChunk] + markPos;
  }

  public final void put(byte b) {
    int p = pos;
    if (p >= limit) {
      p = advance(1);
    }
    bytes[p] = b;
    pos = p + 1;
  }

  public final void putShort(short s) {
    int p = pos;
    if (p > limit - 2) {
      p = advance(2);
    }
    view.putShort(p, s);
    pos = p + 2;
  }

  public final void putChar(char c) {
    int p = pos;
    if (p > limit - 2) {
      p = advance(2);
    }
    view.putChar(p, c);
    pos = p + 2;
  }

  public final void putInt(int i) {
    int p = pos;
    if (p > limit - 4) {
      p = advance(4);
    }
    view.putInt(p, i);
    pos = p + 4;
  }

  public final void putLong(long l) {
    int p = pos;
    if (p > limit - 8) {
      p = advance(8);
    }
    view.putLong(p, l);
    pos = p + 8;
  }

  public final void putFloat(float f) {
    int p = pos;
    if (p > limit - 4) {
      p = advance(4);
    }
    view.putFloat(p, f);
    pos = p + 4;
  }

  public final void putDouble(double d) {
    int p = pos;
    if (p > limit - 8) {
      p = advance(8);
    }
    view.putDouble(p, d);
    pos = p + 8;
  }

  public final void put(byte[] src) {
    put(src, 0, src.length);
  }

  public final void put(byte[] src, int offset, int length) {
    while (length > 0 && !failed) {
      if (pos >= limit) {
        pos = advance(1);
        continue;
      }
      int n = Math.min(length, limit - pos);
      System.arraycopy(src, offset, bytes, pos, n);
      pos += n;
      offset += n;
      length -= n;
    }
  }

  public final void put(ByteBuffer src) {
    while (src.hasRemaining() && !failed) {
      if (pos >= limit) {
        pos = advance(1);
        continue;
      }
      int n = Math.min(src.remaining(), limit - pos);
      src.get(bytes, pos, n);
      pos += n;
    }
  }

  /** Number of chunks currently held. Visible for tests. */
  final int allocatedChunks() {
    return allocatedChunks;
  }

  /**
   * Moves writing to the start of the next chunk, allocating it if needed, or fails the buffer if a
   * write of {@code width} bytes would exceed capacity. Returns the new write position.
   */
  private int advance(int width) {
    if (failed) {
      // the scratch array is full: start over, its contents are never read
      return 0;
    }
    int start = 0;
    if (current >= 0) {
      fills[current] = pos;
      start = starts[current] + pos;
    }
    int next = current + 1;
    int remaining = maxBytes - start;
    if (remaining < width || next == maxChunks) {
      fail();
      return 0;
    }
    int length = Math.min(CHUNK_SIZE, remaining);
    if (next == allocatedChunks) {
      ++allocatedChunks;
      allocate(next, length);
    } else if (chunks[next].length < length) {
      // a short last chunk kept from an earlier payload: this payload needs more of it
      allocate(next, length);
    }
    starts[next] = start;
    limits[next] = Math.min(chunks[next].length, remaining);
    moveTo(next);
    return 0;
  }

  private void allocate(int index, int length) {
    byte[] chunk = new byte[length];
    chunks[index] = chunk;
    views[index] = ByteBuffer.wrap(chunk);
  }

  private void fail() {
    failed = true;
    if (scratchBytes == null) {
      scratchBytes = new byte[SCRATCH_SIZE];
      scratchView = ByteBuffer.wrap(scratchBytes);
    }
    bytes = scratchBytes;
    view = scratchView;
    limit = SCRATCH_SIZE;
  }

  /** Points writes at a chunk, or at nothing when {@code chunk} is -1. Doesn't change position. */
  private void moveTo(int chunk) {
    current = chunk;
    if (chunk < 0) {
      bytes = null;
      view = null;
      limit = 0;
    } else {
      bytes = chunks[chunk];
      view = views[chunk];
      limit = limits[chunk];
    }
  }
}
