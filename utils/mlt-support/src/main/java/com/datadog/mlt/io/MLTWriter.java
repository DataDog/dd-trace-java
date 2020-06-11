package com.datadog.mlt.io;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;
import java.util.stream.Stream;
import lombok.Generated;

/** The MLT binary format writer */
public final class MLTWriter {
  /**
   * Write a single chunk to its binary format
   *
   * @param chunk the chunk
   * @return chunk in its MLT binary format
   */
  public byte[] writeChunk(IMLTChunk chunk) {
    LEB128ByteArrayWriter writer =
        new LEB128ByteArrayWriter(16384); // conservatively pre-allocate 16k byte array
    writeChunk(chunk, writer);
    return writer.toByteArray();
  }

  /**
   * Write multiple chunks into one blob
   *
   * @param chunks the chunk sequence
   * @return the MLT binary format representation of the given chunks sequence
   */
  @Generated // trivial delegating implementation; exclude from jacoco
  public byte[] writeChunks(Stream<IMLTChunk> chunks) {
    LEB128ByteArrayWriter writer = new LEB128ByteArrayWriter(65536); // 64k buffer
    chunks.forEach(chunk -> writeChunk(chunk, writer));
    return writer.toByteArray();
  }

  private void writeChunk(IMLTChunk chunk, LEB128ByteArrayWriter writer) {
    writer
        .writeBytes(MLTConstants.MAGIC) // MAGIC
        .writeByte(chunk.getVersion()) // version
        .writeIntRaw(0) // size; offset = 5
        .writeIntRaw(0) // ptr to constant pools; offset = 9
        .writeLong(chunk.getStartTime()) // start timestamp
        .writeLong(chunk.getDuration()) // duration
        .writeLong(chunk.getThreadId());

    IntSet stringConstants = new IntArraySet();
    IntSet frameConstants = new IntArraySet();
    IntSet stackConstants = new IntArraySet();

    /*
     * Write out the stack trace sequence and collect the constant pool usage.
     * In order collect the data and count it in one pass the intermediary result is written to a separate
     * byte array.
     */
    LEB128ByteArrayWriter stackEventWriter = new LEB128ByteArrayWriter(8192);
    int[] eventCount = new int[1];
    chunk
        .frameSequenceCpIndexes()
        .forEach(
            val -> {
              eventCount[0]++;
              stackEventWriter.writeInt(val);
              if ((val & 0x80000000) == 0) {
                collectStackPtrUsage(
                    val,
                    stringConstants,
                    frameConstants,
                    stackConstants,
                    chunk.getFramePool(),
                    chunk.getStackPool());
              }
            });
    writer.writeInt(eventCount[0]);
    writer.writeBytes(stackEventWriter.toByteArray());

    writer.writeIntRaw(
        MLTConstants.CONSTANT_POOLS_OFFSET, writer.position()); // write the constant pools offset
    writeStringPool(chunk, writer, stringConstants);
    writeFramePool(chunk, writer, frameConstants);
    writeStackPool(chunk, writer, stackConstants);
    writer.writeIntRaw(MLTConstants.CHUNK_SIZE_OFFSET, writer.position()); // write the chunk size
  }

  private void writeStackPool(
      IMLTChunk chunk, LEB128ByteArrayWriter writer, IntSet stackConstants) {
    // write stack pool array
    writer.writeInt(stackConstants.size());
    stackConstants
        .iterator()
        .forEachRemaining(
            (IntConsumer)
                ptr -> {
                  writer.writeInt(ptr);
                  FrameSequence stack = chunk.getStackPool().get(ptr);
                  int cutoff = 5;
                  int depth = stack.length();
                  if (depth > cutoff) {
                    writer
                        .writeByte((byte) 1) // write type
                        .writeInt(cutoff); // number of frames
                    for (int i = 0; i < cutoff - 1; i++) {
                      writer.writeInt(stack.getHeadCpIndex());
                      stack = chunk.getStackPool().get(stack.getSubsequenceCpIndex());
                    }
                    writer.writeInt(stack.getHeadCpIndex()).writeInt(stack.getSubsequenceCpIndex());
                  } else {
                    writer
                        .writeByte((byte) 0) // write type
                        .writeInt(depth); // number of elements
                    for (int i = 0; i < depth; i++) {
                      writer.writeInt(stack.getHeadCpIndex());
                      stack = chunk.getStackPool().get(stack.getSubsequenceCpIndex());
                    }
                  }
                });
  }

  private void writeFramePool(
      IMLTChunk chunk, LEB128ByteArrayWriter writer, IntSet frameConstants) {
    // write frame pool array
    writer.writeInt(frameConstants.size());
    frameConstants
        .iterator()
        .forEachRemaining(
            (IntConsumer)
                ptr -> {
                  FrameElement frame = chunk.getFramePool().get(ptr);
                  writer.writeInt(ptr);
                  writer
                      .writeInt(frame.getOwnerPtr())
                      .writeInt(frame.getMethodPtr())
                      .writeIntRaw(frame.getLine());
                });
  }

  private void writeStringPool(
      IMLTChunk chunk, LEB128ByteArrayWriter writer, IntSet stringConstants) {
    // write constant pool array
    writer.writeInt(stringConstants.size() + 1);
    byte[] threadNameUtf = chunk.getThreadName().getBytes(StandardCharsets.UTF_8);
    writer
        .writeInt(0)
        .writeInt(threadNameUtf.length)
        .writeBytes(threadNameUtf); // 0th CP entry is the thread name
    stringConstants
        .iterator()
        .forEachRemaining(
            (IntConsumer)
                ptr -> {
                  writer.writeInt(ptr);
                  byte[] utfData = chunk.getStringPool().get(ptr).getBytes(StandardCharsets.UTF_8);
                  writer.writeInt(utfData.length).writeBytes(utfData);
                });
  }

  private void collectStackPtrUsage(
      int ptr,
      IntSet stringConstants,
      IntSet frameConstants,
      IntSet stackConstants,
      ConstantPool<FrameElement> framePool,
      ConstantPool<FrameSequence> stackPool) {
    if (ptr > -1) {
      FrameSequence stack = stackPool.get(ptr);
      stackConstants.add(ptr);
      collectFramePtrUsage(stack.getHeadCpIndex(), stringConstants, frameConstants, framePool);
      collectStackPtrUsage(
          stack.getSubsequenceCpIndex(),
          stringConstants,
          frameConstants,
          stackConstants,
          framePool,
          stackPool);
    }
  }

  private void collectFramePtrUsage(
      int ptr,
      IntSet stringConstants,
      IntSet frameConstants,
      ConstantPool<FrameElement> framePool) {
    FrameElement frame = framePool.get(ptr);
    frameConstants.add(ptr);
    stringConstants.add(frame.getMethodPtr());
    stringConstants.add(frame.getOwnerPtr());
  }
}
