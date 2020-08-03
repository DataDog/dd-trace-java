package com.datadog.mlt.io;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import lombok.NonNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** The MLT binary format writer */
public final class MLTWriter {
  /**
   * Write a single chunk to its binary format
   *
   * @param chunk the chunk
   * @return chunk in its MLT binary format
   */
  public static byte[] writeChunk(@NonNull IMLTChunk chunk) {
    if (!chunk.hasStacks()) {
      return null;
    }
    LEB128Writer chunkWriter = LEB128Writer.getInstance();
    writeChunk(chunk, chunkWriter);
    byte[] data = chunkWriter.export();
    chunkWriter.reset();
    return data;
  }

  /**
   * Write out the provided chunk to the given {@linkplain ByteBuffer} consumer.
   * The consumer can assume it is the only party accessing the buffer at the given time
   * and <b>MUST</b> properly manage the buffer by eg. resetting the position and limit once
   * done reading the data.
   * @param chunk the chunk to write out
   * @param dataConsumer the consumer receiving the {@linkplain ByteBuffer} instance containing the chunk data
   */
  public static void writeChunk(@NonNull IMLTChunk chunk, @NonNull Consumer<ByteBuffer> dataConsumer) {
    LEB128Writer chunkWriter = LEB128Writer.getInstance();
    writeChunk(chunk, chunkWriter);
    chunkWriter.export(dataConsumer);
  }

  private static void writeChunk(IMLTChunk chunk, LEB128Writer writer) {
    writer
        .writeBytes(MLTConstants.MAGIC) // MAGIC
        .writeByte(chunk.getVersion()) // version
        .writeIntRaw(0) // size; offset = 5
        .writeIntRaw(0) // ptr to constant pools; offset = 9
        .writeLong(chunk.getStartTime()) // start timestamp
        .writeLong(chunk.getDuration()) // duration
        .writeLong(chunk.getThreadId());

    IntSet stringConstants = new IntOpenHashSet();
    IntSet frameConstants = new IntOpenHashSet();
    IntSet stackConstants = new IntOpenHashSet();

    int[] eventCount = new int[1];
    chunk
        .frameSequenceCpIndexes()
        .forEach(
            val -> {
              eventCount[0]++;
              /*
               * Checking for compression flag - `(topItem & 0x80000000) == 0`, simplified as `topItem >= 0`
               */
              if (val >= 0) {
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
    chunk.frameSequenceCpIndexes().forEach(writer::writeInt);

    writer.writeIntRaw(
        MLTConstants.CONSTANT_POOLS_OFFSET, writer.position()); // write the constant pools offset
    writeStringPool(chunk, writer, stringConstants);
    writeFramePool(chunk, writer, frameConstants);
    writeStackPool(chunk, writer, stackConstants);
    int size = writer.position();
    writer.writeIntRaw(MLTConstants.CHUNK_SIZE_OFFSET, size); // write the chunk size
    if (chunk instanceof MLTChunk) {
      ((MLTChunk)chunk).adjustSize(size);
    }
  }

  private static void writeStackPool(IMLTChunk chunk, LEB128Writer writer, IntSet stackConstants) {
    // write stack pool array
    writer.writeInt(stackConstants.size());
    stackConstants
        .iterator()
        .forEachRemaining(
            (IntConsumer)
                ptr -> {
                  writer.writeInt(ptr);
                  FrameSequence stack = chunk.getStackPool().get(ptr);
                  if (stack.getSubsequenceCpIndex() == -1) {
                    writer.writeByte((byte) 2);
                    writer.writeInt(stack.length());
                    stack
                        .framesFromLeaves()
                        .map(FrameElement::getCpIndex)
                        .forEachOrdered(writer::writeInt);
                  } else {
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
                      writer
                          .writeInt(stack.getHeadCpIndex())
                          .writeInt(stack.getSubsequenceCpIndex());
                    } else {
                      writer
                          .writeByte((byte) 0) // write type
                          .writeInt(depth); // number of elements
                      for (int i = 0; i < depth; i++) {
                        writer.writeInt(stack.getHeadCpIndex());
                        stack = chunk.getStackPool().get(stack.getSubsequenceCpIndex());
                      }
                    }
                  }
                });
  }

  private static void writeFramePool(IMLTChunk chunk, LEB128Writer writer, IntSet frameConstants) {
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

  private static void writeStringPool(IMLTChunk chunk, LEB128Writer writer, IntSet stringConstants) {
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

  private static void collectStackPtrUsage(
      int ptr,
      IntSet stringConstants,
      IntSet frameConstants,
      IntSet stackConstants,
      ConstantPool<FrameElement> framePool,
      ConstantPool<FrameSequence> stackPool) {
    if (ptr > -1) {
      FrameSequence stack = stackPool.get(ptr);
      stackConstants.add(ptr);
      int[] framePtrs = stack.getFrameCpIndexes();
      for (int framePtr : framePtrs) {
        collectFramePtrUsage(framePtr, stringConstants, frameConstants, framePool);
      }
      if (stack.getSubsequenceCpIndex() != -1) {
        collectStackPtrUsage(
            stack.getSubsequenceCpIndex(),
            stringConstants,
            frameConstants,
            stackConstants,
            framePool,
            stackPool);
      }
    }
  }

  private static void collectFramePtrUsage(
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
