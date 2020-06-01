package com.datadog.profiling.mlt.io;

import com.datadog.profiling.util.LEB128ByteArrayWriter;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import lombok.Generated;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;

public final class MLTWriter {
  public byte[] writeChunk(IMLTChunk chunk) {
    LEB128ByteArrayWriter writer =
        new LEB128ByteArrayWriter(16384); // conservatively pre-allocate 16k byte array
    writeChunk(chunk, writer);
    return writer.toByteArray();
  }

  @Generated // trivial delegating implementation; exclude from jacoco
  public byte[] writeChunks(Stream<IMLTChunk> chunks) {
    LEB128ByteArrayWriter writer = new LEB128ByteArrayWriter(65536); // 64k buffer
    chunks.forEach(chunk -> writeChunk(chunk, writer));
    return writer.toByteArray();
  }

  private void writeChunk(IMLTChunk chunk, LEB128ByteArrayWriter writer) {
    writer
        .writeBytes(Constants.MAGIC) // MAGIC
        .writeByte(chunk.getVersion()) // version
        .writeIntRaw(0) // size; offset = 5
        .writeIntRaw(0) // ptr to constant pools; offset = 9
        .writeLong(chunk.getStartTime()) // start timestamp
        .writeLong(System.nanoTime() - chunk.getStartTime()) // duration
        .writeLong(chunk.getThreadId());

    IntHashSet stringConstants = IntHashSet.newSetWith();
    IntHashSet frameConstants = IntHashSet.newSetWith();
    IntHashSet stackConstants = IntHashSet.newSetWith();

    /*
     * Write out the stack trace sequence and collect the constant pool usage.
     * In order collect the data and count it in one pass the intermediary result is written to a separate
     * byte array.
     */
    LEB128ByteArrayWriter stackEventWriter = new LEB128ByteArrayWriter(8192);
    int[] eventCount = new int[1];
    chunk
        .stackPtrs()
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
        Constants.CONSTANT_POOLS_OFFSET, writer.position()); // write the constant pools offset
    writeStringPool(chunk, writer, stringConstants);
    writeFramePool(chunk, writer, frameConstants);
    writeStackPool(chunk, writer, stackConstants);
    writer.writeIntRaw(Constants.CHUNK_SIZE_OFFSET, writer.position()); // write the chunk size
  }

  private void writeStackPool(
      IMLTChunk chunk, LEB128ByteArrayWriter writer, IntHashSet stackConstants) {
    // write stack pool array
    writer.writeInt(stackConstants.size());
    stackConstants.forEach(
        ptr -> {
          writer.writeInt(ptr);
          FrameStack stack = chunk.getStackPool().get(ptr);
          int cutoff = 5;
          int depth = stack.depth();
          if (depth > cutoff) {
            writer
                .writeByte((byte) 1) // write type
                .writeInt(cutoff); // number of frames
            for (int i = 0; i < cutoff - 1; i++) {
              writer.writeInt(stack.getHeadPtr());
              stack = chunk.getStackPool().get(stack.getSubtreePtr());
            }
            writer.writeInt(stack.getHeadPtr()).writeInt(stack.getSubtreePtr());
          } else {
            writer
                .writeByte((byte) 0) // write type
                .writeInt(depth); // number of elements
            for (int i = 0; i < depth; i++) {
              writer.writeInt(stack.getHeadPtr());
              stack = chunk.getStackPool().get(stack.getSubtreePtr());
            }
          }
        });
  }

  private void writeFramePool(
      IMLTChunk chunk, LEB128ByteArrayWriter writer, IntHashSet frameConstants) {
    // write frame pool array
    writer.writeInt(frameConstants.size());
    frameConstants.forEach(
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
      IMLTChunk chunk, LEB128ByteArrayWriter writer, IntHashSet stringConstants) {
    // write constant pool array
    writer.writeInt(stringConstants.size() + 1);
    byte[] threadNameUtf = chunk.getThreadName().getBytes(StandardCharsets.UTF_8);
    writer
        .writeInt(0)
        .writeInt(threadNameUtf.length)
        .writeBytes(threadNameUtf); // 0th CP entry is the thread name
    stringConstants.forEach(
        ptr -> {
          writer.writeInt(ptr);
          byte[] utfData = chunk.getStringPool().get(ptr).getBytes(StandardCharsets.UTF_8);
          writer.writeInt(utfData.length).writeBytes(utfData);
        });
  }

  private void collectStackPtrUsage(
      int ptr,
      IntHashSet stringConstants,
      IntHashSet frameConstants,
      IntHashSet stackConstants,
      ConstantPool<FrameElement> framePool,
      ConstantPool<FrameStack> stackPool) {
    if (ptr > -1) {
      FrameStack stack = stackPool.get(ptr);
      stackConstants.add(ptr);
      collectFramePtrUsage(stack.getHeadPtr(), stringConstants, frameConstants, framePool);
      collectStackPtrUsage(
          stack.getSubtreePtr(),
          stringConstants,
          frameConstants,
          stackConstants,
          framePool,
          stackPool);
    }
  }

  private void collectFramePtrUsage(
      int ptr,
      IntHashSet stringConstants,
      IntHashSet frameConstants,
      ConstantPool<FrameElement> framePool) {
    FrameElement frame = framePool.get(ptr);
    frameConstants.add(ptr);
    stringConstants.add(frame.getMethodPtr());
    stringConstants.add(frame.getOwnerPtr());
  }
}
