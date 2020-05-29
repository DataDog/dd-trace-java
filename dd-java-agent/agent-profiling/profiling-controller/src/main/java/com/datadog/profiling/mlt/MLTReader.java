package com.datadog.profiling.mlt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MLTReader {
  private static final byte[] MAGIC = new byte[]{'D', 'D', 0, 9};

  public List<MLTChunk> readMLT(byte[] data) {
    ByteArrayReader r = new ByteArrayReader(data);
    List<MLTChunk> chunks = new ArrayList<>();
    while (r.hasMore()) {
      int chunkBase = r.position();
      byte[] magic = r.readBytes(4);
      if (!Arrays.equals(MAGIC, magic)) {
        throw new IllegalStateException();
      }

      byte version = r.readByte();
      int size = r.readIntRaw();
      int cpOffset = r.readIntRaw();
      long ts = r.readLong();
      long duration = r.readLong();
      long threadId = r.readLong();

      int eventStart = r.getAndSetPos(cpOffset + chunkBase);
      ConstantPool<String> stringPool = new ConstantPool<>();
      int cpSize = r.readInt();
      for (int i = 0; i < cpSize; i++) {
        int ptr = r.readInt();
        stringPool.insert(r.readUTF(), ptr);
      }
      ConstantPool<FrameElement> framePool = new ConstantPool<>();
      cpSize = r.readInt();
      for (int i = 0; i < cpSize; i++) {
        int ptr = r.readInt();
        int ownerPtr = r.readInt();
        int methodPtr = r.readInt();
        int line = r.readIntRaw();

        framePool.insert(new FrameElement(ownerPtr, methodPtr, line, stringPool), ptr);
      }
      ConstantPool<StackElement1> stackPool = new ConstantPool<>();
      cpSize = r.readInt();
      for (int i = 0; i < cpSize; i++) {
        int ptr = r.readInt();
        byte type = r.readByte();
        int[] framePtrs = new int[0];
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
        stackPool.insert(new StackElement1(framePtrs, subtreePtr, framePool, stackPool), ptr);
      }
      int endpos = r.getAndSetPos(eventStart);
      int stacksLen = r.readInt();
      int ptr = 0;
      StackElement1 lastElement = null;
      List<StackElement1> stackElements = new ArrayList<>(stacksLen);
      for (int i = 0; i < stacksLen; i++) {
        int cnt = 1;
        ptr = r.readInt();
        if ((ptr & 0x80000000) == 0x80000000) {
          if (lastElement == null) {
            throw new IllegalStateException();
          }
          cnt = (ptr & 0x7fffffff);
        } else {
          lastElement = stackPool.get(ptr);
        }
        for (int j = 0; j < cnt; j++) {
          stackElements.add(lastElement);
        }
      }
      chunks.add(new MLTChunk(version, size, ts, duration, threadId, stringPool.get(0), stackElements));
      r.getAndSetPos(endpos); // move to the end of chunk
    }
    return chunks;
  }
}
