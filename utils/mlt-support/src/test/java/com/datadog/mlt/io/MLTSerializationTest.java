package com.datadog.mlt.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NonNull;
import org.junit.jupiter.api.Test;

public class MLTSerializationTest {
  @Test
  public void nullChunkReaderTest() {
    assertThrows(NullPointerException.class, () -> MLTReader.readMLTChunks(null));
  }

  @Test
  public void emptyChunkReaderTest() {
    List<IMLTChunk> chunks = MLTReader.readMLTChunks(new byte[0]);
    assertNotNull(chunks);
    assertEquals(0, chunks.size());
  }

  @Test
  public void garbageChunkReaderTest() {
    Random rnd = new Random();
    byte[] data = new byte[8192];
    rnd.nextBytes(data);

    assertThrows(IllegalStateException.class, () -> MLTReader.readMLTChunks(data));
  }

  @Test
  public void nullChunkWriterTest() {
    assertThrows(NullPointerException.class, () -> MLTWriter.writeChunk(null));
  }

  @Test
  public void nullConsumerWriterTest() {
    assertThrows(NullPointerException.class, () -> MLTWriter.writeChunk(getMltChunk(), null));
  }

  @Test
  public void checkConsumerWriter() {
    AtomicReference<ByteBuffer> ref = new AtomicReference<>();
    try {
      MLTWriter.writeChunk(
        getMltChunk(),
        bb -> {
          ref.set(bb);
        });
      ByteBuffer bb = ref.get();

      assertNotNull(bb);
    } finally {
      LEB128ByteBufferWriter.discardBuffer();
    }
  }

  @Test
  public void smokeTest() {
    MLTChunk chunk = getMltChunk();

    byte[] data = MLTWriter.writeChunk(chunk);
    assertNotNull(data);

    System.out.println("Chunk data: " + Arrays.toString(data));

    List<IMLTChunk> restoredChunks = MLTReader.readMLTChunks(data);
    assertNotNull(restoredChunks);
    assertEquals(1, restoredChunks.size());

    IMLTChunk restoredChunk = restoredChunks.get(0);
    assertEquals(chunk, restoredChunk);
  }

  @NonNull
  private MLTChunk getMltChunk() {
    MLTChunkBuilder builder = new MLTChunkBuilder(System.currentTimeMillis(), 1, "main");
    MLTChunk chunk =
        builder
            .addStack()
            .addFrame("ClassA", "main", -1)
            .addFrame("ClassA", "m1", -1)
            .addFrame("ClassC", "m1", -1)
            .build()
            .addStack()
            .addFrame("ClassB", "main", -1)
            .addFrame("ClassA", "m2", -1)
            .addFrame("ClassC", "m4", -1)
            .addFrame("ClassD", "m1", -1)
            .build()
            .build(10000);
    return chunk;
  }
}
