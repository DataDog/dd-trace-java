package datadog.trace.util;

import static datadog.trace.util.ChunkedBuffer.CHUNK_SIZE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkedBufferTest {

  private static final int TWO_CHUNKS = 2 * CHUNK_SIZE;
  private static final int THREE_CHUNKS = 3 * CHUNK_SIZE;

  @Test
  void chunkObjectFitsIn256KiBForEveryArrayHeaderLayout() {
    for (int header : new int[] {12, 16, 24}) {
      int objectSize = (header + CHUNK_SIZE + 7) & ~7;
      assertTrue(objectSize <= 256 * 1024, "header " + header + " gives " + objectSize);
    }
  }

  @Test
  void capacityIsExact() {
    assertEquals(5 << 20, new ChunkedBuffer(5 << 20, CHUNK_SIZE).capacity());
    assertEquals(1024, new ChunkedBuffer(1024, CHUNK_SIZE).capacity());
    assertEquals(0, new ChunkedBuffer(0, CHUNK_SIZE).capacity());
  }

  @Test
  void lastChunkIsShortenedToFitCapacity() {
    ChunkedBuffer buffer = new ChunkedBuffer(CHUNK_SIZE + 10, CHUNK_SIZE);
    buffer.put(new byte[CHUNK_SIZE + 2]);
    buffer.putLong(5L);
    assertTrue(buffer.mark());
    assertEquals(CHUNK_SIZE + 10, buffer.completedBytes());

    // a short last chunk can still be too small for the next primitive
    buffer.putShort((short) 1);
    assertFalse(buffer.mark());
  }

  @Test
  void unusedChunkTailsDoNotReduceCapacity() {
    ChunkedBuffer buffer = new ChunkedBuffer(TWO_CHUNKS, CHUNK_SIZE);
    buffer.put(new byte[CHUNK_SIZE - 3]);
    // leaves 3 unused bytes at the end of the first chunk
    buffer.putLong(1L);
    buffer.put(new byte[TWO_CHUNKS - (CHUNK_SIZE - 3) - 8]);
    assertTrue(buffer.mark());
    assertEquals(TWO_CHUNKS, buffer.completedBytes());
    assertEquals(TWO_CHUNKS, concat(buffer.completedChunks()).length);

    buffer.put((byte) 1);
    assertFalse(buffer.mark());
  }

  @Test
  void keptShortChunkIsReplacedWhenAPayloadNeedsMoreOfIt() {
    ChunkedBuffer buffer = new ChunkedBuffer(CHUNK_SIZE + 10, CHUNK_SIZE);
    // no unused tail, so the second chunk only needs to be 10 bytes long
    buffer.put(new byte[CHUNK_SIZE + 10]);
    assertTrue(buffer.mark());
    buffer.reset();
    assertEquals(2, buffer.allocatedChunks());

    // a 4 byte tail in the first chunk means this payload needs 14 bytes of the second
    buffer.put(new byte[CHUNK_SIZE - 4]);
    buffer.putLong(1L);
    buffer.put(new byte[6]);
    assertTrue(buffer.mark());
    assertEquals(CHUNK_SIZE + 10, buffer.completedBytes());
  }

  @Test
  void smallBufferIsOneSmallChunk() {
    ChunkedBuffer buffer = new ChunkedBuffer(16, CHUNK_SIZE);
    buffer.putLong(1L);
    buffer.putLong(2L);
    assertTrue(buffer.mark());
    assertEquals(16, buffer.completedChunks().get(0).array().length);

    buffer.put((byte) 3);
    assertFalse(buffer.mark());
    assertEquals(16, buffer.completedBytes());
  }

  @Test
  void zeroCapacityRejectsEveryMessage() {
    ChunkedBuffer buffer = new ChunkedBuffer(0, CHUNK_SIZE);
    buffer.putInt(1);
    buffer.put(new byte[10]);
    assertFalse(buffer.mark());
    assertFalse(buffer.isDirty());
    assertEquals(0, buffer.allocatedChunks());
  }

  @Test
  void allocatesNothingBeforeFirstWrite() {
    ChunkedBuffer buffer = new ChunkedBuffer(TWO_CHUNKS, CHUNK_SIZE);
    assertEquals(0, buffer.allocatedChunks());
    buffer.mark();
    buffer.reset();
    assertEquals(0, buffer.allocatedChunks());
    assertFalse(buffer.isDirty());
    assertTrue(buffer.completedChunks().isEmpty());
  }

  @Test
  void typedPutsAreBigEndianAcrossChunkBoundaries() {
    // a mix of widths, repeated enough to cross several chunk boundaries at varying offsets
    ChunkedBuffer buffer = new ChunkedBuffer(THREE_CHUNKS, CHUNK_SIZE);
    ByteBuffer expected = ByteBuffer.allocate(THREE_CHUNKS);
    int i = 0;
    while (expected.position() < TWO_CHUNKS + CHUNK_SIZE / 2) {
      buffer.put((byte) i);
      expected.put((byte) i);
      buffer.putShort((short) (i * 3));
      expected.putShort((short) (i * 3));
      buffer.putChar((char) (i * 5));
      expected.putChar((char) (i * 5));
      buffer.putInt(i * 7);
      expected.putInt(i * 7);
      buffer.putLong(i * 11L);
      expected.putLong(i * 11L);
      buffer.putFloat(i * 1.5f);
      expected.putFloat(i * 1.5f);
      buffer.putDouble(i * 2.5);
      expected.putDouble(i * 2.5);
      assertTrue(buffer.mark());
      ++i;
    }
    expected.flip();

    assertTrue(buffer.allocatedChunks() >= 3, "test should cross at least two boundaries");
    assertArrayEquals(toArray(expected), concat(buffer.completedChunks()));
    assertEquals(expected.remaining(), buffer.completedBytes());
    assertEquals(i, buffer.messageCount());
  }

  @Test
  void primitiveThatDoesNotFitStartsTheNextChunk() {
    ChunkedBuffer buffer = new ChunkedBuffer(TWO_CHUNKS, CHUNK_SIZE);
    buffer.put(new byte[CHUNK_SIZE - 3]);
    buffer.putLong(0x0102030405060708L);
    buffer.mark();

    List<ByteBuffer> chunks = buffer.completedChunks();
    assertEquals(2, chunks.size());
    assertEquals(CHUNK_SIZE - 3, chunks.get(0).remaining());
    assertEquals(8, chunks.get(1).remaining());
    assertEquals(0x0102030405060708L, chunks.get(1).getLong());
    assertEquals(CHUNK_SIZE + 5, buffer.completedBytes());
  }

  @Test
  void byteArraysSplitAcrossChunks() {
    ChunkedBuffer buffer = new ChunkedBuffer(THREE_CHUNKS, CHUNK_SIZE);
    byte[] data = sequence(CHUNK_SIZE + CHUNK_SIZE / 2);
    buffer.put((byte) -1);
    buffer.put(data, 0, data.length);
    buffer.mark();

    List<ByteBuffer> chunks = buffer.completedChunks();
    assertEquals(2, chunks.size());
    assertEquals(CHUNK_SIZE, chunks.get(0).remaining());
    byte[] all = concat(chunks);
    assertEquals(-1, all[0]);
    byte[] copied = new byte[data.length];
    System.arraycopy(all, 1, copied, 0, data.length);
    assertArrayEquals(data, copied);
  }

  @Test
  void byteBufferSplitsAcrossChunks() {
    ChunkedBuffer buffer = new ChunkedBuffer(THREE_CHUNKS, CHUNK_SIZE);
    byte[] data = sequence(CHUNK_SIZE + 100);
    ByteBuffer src = ByteBuffer.wrap(data);
    buffer.put(src);
    buffer.mark();

    assertFalse(src.hasRemaining());
    assertArrayEquals(data, concat(buffer.completedChunks()));
  }

  @Test
  void emptyMarksAreNotCounted() {
    ChunkedBuffer buffer = new ChunkedBuffer(TWO_CHUNKS, CHUNK_SIZE);
    buffer.mark();
    buffer.mark();
    assertEquals(0, buffer.messageCount());
    buffer.put((byte) 1);
    buffer.mark();
    buffer.mark();
    assertEquals(1, buffer.messageCount());
    assertTrue(buffer.isDirty());
  }

  @Test
  void rewindDiscardsUnmarkedBytesAcrossChunks() {
    ChunkedBuffer buffer = new ChunkedBuffer(THREE_CHUNKS, CHUNK_SIZE);
    buffer.putInt(42);
    buffer.mark();
    // spill the unfinished message into a second chunk
    buffer.put(new byte[CHUNK_SIZE]);

    buffer.rewind();
    buffer.putInt(43);
    buffer.mark();

    List<ByteBuffer> chunks = buffer.completedChunks();
    assertEquals(1, chunks.size());
    assertEquals(8, chunks.get(0).remaining());
    assertEquals(42, chunks.get(0).getInt());
    assertEquals(43, chunks.get(0).getInt());
    assertEquals(2, buffer.messageCount());
  }

  @Test
  void rewindBeforeAnyMarkEmptiesTheBuffer() {
    ChunkedBuffer buffer = new ChunkedBuffer(TWO_CHUNKS, CHUNK_SIZE);
    buffer.put(new byte[CHUNK_SIZE + 10]);
    buffer.rewind();
    assertFalse(buffer.isDirty());
    assertTrue(buffer.completedChunks().isEmpty());

    buffer.putShort((short) 7);
    buffer.mark();
    assertEquals(7, buffer.completedChunks().get(0).getShort());
  }

  @Test
  void overflowFailsWithoutThrowingAndMarkRewinds() {
    ChunkedBuffer buffer = new ChunkedBuffer(TWO_CHUNKS, CHUNK_SIZE);
    byte[] first = sequence(CHUNK_SIZE + 10);
    buffer.put(first);
    assertTrue(buffer.mark());

    // runs past capacity with every kind of write: none of them throw
    buffer.put(new byte[CHUNK_SIZE]);
    for (int i = 0; i < 1000; ++i) {
      buffer.put((byte) i);
      buffer.putShort((short) i);
      buffer.putChar((char) i);
      buffer.putInt(i);
      buffer.putLong(i);
      buffer.putFloat(i);
      buffer.putDouble(i);
      buffer.put(new byte[100]);
      buffer.put(ByteBuffer.allocate(100));
    }

    assertFalse(buffer.mark());
    assertArrayEquals(first, concat(buffer.completedChunks()));
    assertEquals(1, buffer.messageCount());

    // the failure is cleared and the buffer takes writes again
    buffer.putInt(99);
    assertTrue(buffer.mark());
    assertEquals(2, buffer.messageCount());
    assertEquals(CHUNK_SIZE + 14, buffer.completedBytes());
  }

  @Test
  void messageLargerThanCapacityFailsFromEmpty() {
    ChunkedBuffer buffer = new ChunkedBuffer(CHUNK_SIZE, CHUNK_SIZE);
    buffer.put(new byte[CHUNK_SIZE + 1]);
    assertFalse(buffer.mark());
    assertFalse(buffer.isDirty());
    assertTrue(buffer.completedChunks().isEmpty());
  }

  @Test
  void writesFillCapacityExactly() {
    ChunkedBuffer buffer = new ChunkedBuffer(TWO_CHUNKS, CHUNK_SIZE);
    buffer.put(new byte[TWO_CHUNKS - 8]);
    buffer.putLong(1L);
    assertTrue(buffer.mark());
    assertEquals(TWO_CHUNKS, buffer.completedBytes());

    buffer.put((byte) 1);
    assertFalse(buffer.mark());
    assertEquals(TWO_CHUNKS, buffer.completedBytes());
  }

  @Test
  void rewindAlsoClearsFailure() {
    ChunkedBuffer buffer = new ChunkedBuffer(CHUNK_SIZE, CHUNK_SIZE);
    buffer.put(new byte[CHUNK_SIZE + 1]);
    buffer.rewind();
    buffer.putInt(5);
    assertTrue(buffer.mark());
    assertEquals(5, buffer.completedChunks().get(0).getInt());
  }

  @Test
  void resetKeepsTheChunksTheCompletedMessagesUsed() {
    ChunkedBuffer buffer = new ChunkedBuffer(5 * CHUNK_SIZE, CHUNK_SIZE);
    buffer.put(new byte[2 * CHUNK_SIZE + 1]);
    buffer.mark();
    assertEquals(3, buffer.allocatedChunks());
    buffer.reset();
    assertEquals(3, buffer.allocatedChunks());
    assertFalse(buffer.isDirty());
    assertEquals(0, buffer.messageCount());
    assertTrue(buffer.completedChunks().isEmpty());
  }

  @Test
  void resetReleasesHalfTheSurplusEachTime() {
    ChunkedBuffer buffer = new ChunkedBuffer(20 * CHUNK_SIZE, CHUNK_SIZE);
    buffer.put(new byte[18 * CHUNK_SIZE + 1]);
    buffer.mark();
    buffer.reset();
    assertEquals(19, buffer.allocatedChunks());

    // quiet cycles that each need one chunk: the surplus halves each time
    int[] expected = {10, 5, 3, 2, 1, 1};
    for (int kept : expected) {
      buffer.putInt(1);
      buffer.mark();
      buffer.reset();
      assertEquals(kept, buffer.allocatedChunks());
    }
  }

  @Test
  void growthIsNotHalved() {
    ChunkedBuffer buffer = new ChunkedBuffer(5 * CHUNK_SIZE, CHUNK_SIZE);
    buffer.putInt(1);
    buffer.mark();
    buffer.reset();
    assertEquals(1, buffer.allocatedChunks());

    buffer.put(new byte[3 * CHUNK_SIZE + 1]);
    buffer.mark();
    buffer.reset();
    assertEquals(4, buffer.allocatedChunks());
  }

  @Test
  void resetIgnoresUnfinishedAndFailedMessages() {
    ChunkedBuffer buffer = new ChunkedBuffer(3 * CHUNK_SIZE, CHUNK_SIZE);
    buffer.putInt(1);
    buffer.mark();
    // an oversized message touches every chunk before failing
    buffer.put(new byte[4 * CHUNK_SIZE]);
    buffer.reset();
    assertEquals(1, buffer.allocatedChunks());
  }

  @Test
  void resetReusesRetainedChunks() {
    ChunkedBuffer buffer = new ChunkedBuffer(TWO_CHUNKS, CHUNK_SIZE);
    buffer.put(sequence(CHUNK_SIZE + 10));
    buffer.mark();
    ByteBuffer before = buffer.completedChunks().get(1);
    buffer.reset();

    buffer.put(new byte[CHUNK_SIZE]);
    buffer.putInt(7);
    buffer.mark();
    ByteBuffer after = buffer.completedChunks().get(1);
    assertTrue(before.array() == after.array(), "second chunk is reused, not reallocated");
  }

  @Test
  void completedChunksAreIndependentViews() {
    ChunkedBuffer buffer = new ChunkedBuffer(TWO_CHUNKS, CHUNK_SIZE);
    buffer.putLong(3L);
    buffer.mark();

    ByteBuffer first = buffer.completedChunks().get(0);
    first.getLong();
    assertFalse(first.hasRemaining());

    ByteBuffer second = buffer.completedChunks().get(0);
    assertEquals(8, second.remaining());
    assertEquals(3L, second.getLong());
  }

  @Test
  void subclassCanThrowWhenCapacityIsExceeded() {
    ChunkedBuffer buffer =
        new ChunkedBuffer(CHUNK_SIZE) {
          @Override
          protected void onCapacityExceeded() {
            throw new IllegalStateException("full");
          }
        };
    buffer.putInt(1);
    assertTrue(buffer.mark());

    assertThrows(IllegalStateException.class, () -> buffer.put(new byte[CHUNK_SIZE]));

    // the writer stopped at the failure point; rewinding clears it
    buffer.rewind();
    buffer.putInt(2);
    assertTrue(buffer.mark());
    assertEquals(8, buffer.completedBytes());
  }

  @Test
  void defaultFirstChunkIsSmall() {
    ChunkedBuffer buffer = new ChunkedBuffer(5 << 20);
    buffer.putInt(1);
    assertTrue(buffer.mark());
    assertEquals(
        ChunkedBuffer.DEFAULT_FIRST_CHUNK_SIZE, buffer.completedChunks().get(0).array().length);
  }

  @Test
  void chunksStartSmallAndDoubleUpToChunkSize() {
    ChunkedBuffer buffer = new ChunkedBuffer(4 * CHUNK_SIZE, 4096);
    buffer.put(new byte[4096 + 8192 + 16384 + 32768 + 65536 + 131072 + CHUNK_SIZE + 1]);
    assertTrue(buffer.mark());

    List<ByteBuffer> chunks = buffer.completedChunks();
    int[] expected = {4096, 8192, 16384, 32768, 65536, 131072, CHUNK_SIZE, 1};
    assertEquals(expected.length, chunks.size());
    for (int i = 0; i < expected.length; ++i) {
      assertEquals(expected[i], chunks.get(i).remaining(), "chunk " + i);
    }
  }

  @Test
  void smallPayloadKeepsOnlyASmallChunk() {
    ChunkedBuffer buffer = new ChunkedBuffer(32 << 20, 4096);
    buffer.put(new byte[100]);
    assertTrue(buffer.mark());
    assertEquals(4096, buffer.completedChunks().get(0).array().length);
    buffer.reset();
    assertEquals(1, buffer.allocatedChunks());
  }

  @Test
  void capacityIsExactWithSmallFirstChunks() {
    ChunkedBuffer buffer = new ChunkedBuffer(300_000, 4096);
    // primitives leave unused tails in the small chunks
    for (int i = 0; i < 300_000 / 8; ++i) {
      buffer.putLong(i);
    }
    assertTrue(buffer.mark());
    assertEquals(300_000, buffer.completedBytes());

    buffer.put((byte) 1);
    assertFalse(buffer.mark());
  }

  @Test
  void firstChunkSizeIsLargeEnoughForAnyPrimitive() {
    ChunkedBuffer buffer = new ChunkedBuffer(1024, 1);
    buffer.putLong(7L);
    assertTrue(buffer.mark());
    assertEquals(7L, buffer.completedChunks().get(0).getLong());
  }

  private static byte[] sequence(int length) {
    byte[] data = new byte[length];
    for (int i = 0; i < length; ++i) {
      data[i] = (byte) (i * 31 + 7);
    }
    return data;
  }

  private static byte[] concat(List<ByteBuffer> chunks) {
    int total = 0;
    for (ByteBuffer chunk : chunks) {
      total += chunk.remaining();
    }
    ByteBuffer all = ByteBuffer.allocate(total);
    for (ByteBuffer chunk : chunks) {
      all.put(chunk.duplicate());
    }
    return all.array();
  }

  private static byte[] toArray(ByteBuffer buffer) {
    byte[] array = new byte[buffer.remaining()];
    buffer.duplicate().get(array);
    return array;
  }
}
