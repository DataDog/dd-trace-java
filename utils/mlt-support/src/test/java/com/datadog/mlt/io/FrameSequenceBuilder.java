package com.datadog.mlt.io;

import java.util.ArrayList;
import java.util.List;

/** A support class for fluently building {@linkplain FrameSequence} instances. Not thread-safe. */
public final class FrameSequenceBuilder {
  private final ConstantPool<String> stringConstantPool;
  private final ConstantPool<FrameElement> frameConstantPool;
  private final ConstantPool<FrameSequence> stackConstantPool;
  private final List<FrameElement> stack = new ArrayList<>();

  private final MLTChunkBuilder parent;

  private boolean isBuilt;

  public FrameSequenceBuilder(
      MLTChunkBuilder parent,
      ConstantPool<String> stringConstantPool,
      ConstantPool<FrameElement> frameConstantPool,
      ConstantPool<FrameSequence> stackConstantPool) {
    this.parent = parent;
    this.stringConstantPool = stringConstantPool;
    this.frameConstantPool = frameConstantPool;
    this.stackConstantPool = stackConstantPool;
  }

  public FrameSequenceBuilder addFrame(String owner, String method, int line) {
    if (isBuilt) {
      throw new UnsupportedOperationException("Can not add data to an already built builder");
    }
    stack.add(new FrameElement(owner, method, line, stringConstantPool, frameConstantPool));
    return this;
  }

  public MLTChunkBuilder build() {
    try {
      int[] indices = new int[stack.size()];
      for (int i = 0; i < indices.length; i++) {
        indices[i] = frameConstantPool.getOrInsert(stack.get(i));
      }
      FrameSequence result = new FrameSequence(indices, frameConstantPool, stackConstantPool);
      parent.addFrameSequence(result);
      return parent;
    } finally {
      isBuilt = true;
    }
  }
}
