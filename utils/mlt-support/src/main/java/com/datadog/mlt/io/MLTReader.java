package com.datadog.mlt.io;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The MLT binary format reader */
public final class MLTReader {
  /**
   * Read all subsequent MLT chunks from the given data
   *
   * @param data the MLT binary format data
   * @return the list of all chunks contained in the input data in order of appearance
   */
  public List<MLTChunk> readMLTChunks(byte[] data) {
    LEB128ByteArrayReader r = new LEB128ByteArrayReader(data);
    List<MLTChunk> chunks = new ArrayList<>();
    while (r.hasMore()) {
      chunks.add(readMLTChunk(r));
    }
    return chunks;
  }

  private MLTChunk readMLTChunk(LEB128ByteArrayReader r) {
    int chunkBase = r.position();
    byte[] magic = r.readBytes(4);
    if (!Arrays.equals(MLTConstants.MAGIC, magic)) {
      throw new IllegalStateException();
    }

    byte version = r.readByte();
    int size = r.readIntRaw();
    int cpOffset = r.readIntRaw();
    long ts = r.readLong();
    long duration = r.readLong();
    long threadId = r.readLong();

    int eventStart =
        r.getAndSetPos(cpOffset + chunkBase); // save the position and jump to constant pools
    ConstantPool<String> stringPool = readStringConstantPool(r);
    ConstantPool<FrameElement> framePool = readFrameConstantPool(r, stringPool);
    ConstantPool<FrameSequence> stackPool = readStackConstantPool(r, framePool);

    // save the chunk end position and restore the event sequence position
    int endpos = r.getAndSetPos(eventStart);
    List<FrameSequence> stackElements = readStackEvents(r, stackPool);

    MLTChunk chunk =
        new MLTChunk(
            version,
            size,
            ts,
            duration,
            threadId,
            stringPool.get(0),
            stringPool,
            framePool,
            stackPool,
            stackElements);
    r.getAndSetPos(endpos); // move to the end of chunk
    return chunk;
  }

  private List<FrameSequence> readStackEvents(
      LEB128ByteArrayReader r, ConstantPool<FrameSequence> stackPool) {
    int eventCount = r.readInt();
    int ptr = 0;
    FrameSequence lastElement = null;
    List<FrameSequence> stackElements = new ArrayList<>(eventCount);
    for (int i = 0; i < eventCount; i++) {
      int cnt = 1;
      ptr = r.readInt();
      if ((ptr & MLTConstants.EVENT_REPEAT_FLAG) == MLTConstants.EVENT_REPEAT_FLAG) {
        if (lastElement == null) {
          throw new IllegalStateException();
        }
        cnt = (ptr & MLTConstants.EVENT_REPEAT_MASK);
      } else {
        lastElement = stackPool.get(ptr);
      }
      for (int j = 0; j < cnt; j++) {
        stackElements.add(lastElement);
      }
    }
    return stackElements;
  }

  private ConstantPool<FrameSequence> readStackConstantPool(
      LEB128ByteArrayReader r, ConstantPool<FrameElement> framePool) {
    int cpSize;
    ConstantPool<FrameSequence> stackPool = new ConstantPool<>();
    cpSize = r.readInt();
    int[] emptyFrames = new int[0];
    for (int i = 0; i < cpSize; i++) {
      int ptr = r.readInt();
      byte type = r.readByte();
      int[] framePtrs = emptyFrames;
      int subtreePtr = -1;
      if (type == 0) {
        int framesLen = r.readInt();
        framePtrs = new int[framesLen];
        for (int j = 0; j < framesLen; j++) {
          framePtrs[j] = r.readInt();
        }
      } else if (type == 1) {
        int len = r.readInt();
        framePtrs = new int[len];
        for (int j = 0; j < framePtrs.length; j++) {
          framePtrs[j] = r.readInt();
        }
        subtreePtr = r.readInt();
      }
      stackPool.insert(ptr, new FrameSequence(ptr, framePtrs, subtreePtr, framePool, stackPool));
    }
    return stackPool;
  }

  private ConstantPool<FrameElement> readFrameConstantPool(
      LEB128ByteArrayReader r, ConstantPool<String> stringPool) {
    int cpSize;
    ConstantPool<FrameElement> framePool = new ConstantPool<>();
    cpSize = r.readInt();
    for (int i = 0; i < cpSize; i++) {
      int ptr = r.readInt();
      int ownerPtr = r.readInt();
      int methodPtr = r.readInt();
      int line = r.readIntRaw();

      framePool.insert(ptr, new FrameElement(ownerPtr, methodPtr, line, stringPool));
    }
    return framePool;
  }

  private ConstantPool<String> readStringConstantPool(LEB128ByteArrayReader r) {
    ConstantPool<String> stringPool = new ConstantPool<>();
    int cpSize = r.readInt();
    for (int i = 0; i < cpSize; i++) {
      int ptr = r.readInt();
      stringPool.insert(ptr, r.readUTF());
    }
    return stringPool;
  }
}
