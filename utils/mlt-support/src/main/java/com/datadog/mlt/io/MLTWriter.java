package com.datadog.mlt.io;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** The MLT binary format writer */
public final class MLTWriter {
  private static final int CHUNK_WRITER_CAPACITY = 512 * 1024; // initial 512kB for chunk writer
  private static final int FRAME_STACK_WRITER_CAPACITY =
      256 * 1024; // initial 256kB for frame stack writer
  private final LEB128Writer chunkWriter = LEB128Writer.getInstance(CHUNK_WRITER_CAPACITY);
  private final LEB128Writer frameStackDataWriter =
      LEB128Writer.getInstance(FRAME_STACK_WRITER_CAPACITY);

  /**
   * Write a single chunk to its binary format
   *
   * @param chunk the chunk
   * @return chunk in its MLT binary format
   */
  public byte[] writeChunk(IMLTChunk chunk) {
    writeChunk(chunk, chunkWriter);
    byte[] data = chunkWriter.export();
    chunkWriter.reset();
    return data;
  }

  public void writeChunk(IMLTChunk chunk, Consumer<ByteBuffer> dataConsumer) {
    writeChunk(chunk, chunkWriter);
    chunkWriter.export(dataConsumer);
  }

  private void writeChunk(IMLTChunk chunk, LEB128Writer writer) {
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
     * writer.
     */
    LEB128Writer stackEventWriter = frameStackDataWriter;
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
    writer.writeBytes(stackEventWriter.export());

    writer.writeIntRaw(
        MLTConstants.CONSTANT_POOLS_OFFSET, writer.position()); // write the constant pools offset
    writeStringPool(chunk, writer, stringConstants);
    writeFramePool(chunk, writer, frameConstants);
    writeStackPool(chunk, writer, stackConstants);
    writer.writeIntRaw(MLTConstants.CHUNK_SIZE_OFFSET, writer.position()); // write the chunk size
  }

  private void writeStackPool(IMLTChunk chunk, LEB128Writer writer, IntSet stackConstants) {
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
                    stack.frames().map(FrameElement::getCpIndex).forEachOrdered(writer::writeInt);
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

  private void writeFramePool(IMLTChunk chunk, LEB128Writer writer, IntSet frameConstants) {
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

  private void writeStringPool(IMLTChunk chunk, LEB128Writer writer, IntSet stringConstants) {
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
