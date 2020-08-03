package com.datadog.mlt.io;

import java.util.ArrayList;
import java.util.List;

/** A support class for fluently building {@linkplain IMLTChunk} instances. Not thread-safe. */
public final class MLTChunkBuilder {
  private final ConstantPool<String> stringConstantPool = new ConstantPool<>(1);
  private final ConstantPool<FrameElement> frameConstantPool = new ConstantPool<>();
  private final ConstantPool<FrameSequence> stackConstantPool = new ConstantPool<>();

  private final long startTs;
  private final long threadId;
  private final String threadName;

  private final List<FrameSequence> stacks = new ArrayList<>();

  private boolean isBuilt = false;

  public MLTChunkBuilder(long startTs, long threadId, String threadName) {
    this.startTs = startTs;
    this.threadId = threadId;
    this.threadName = threadName;
    this.stringConstantPool.insert(0, threadName); // add the thread name to CP
  }

  public FrameSequenceBuilder addStack() {
    if (isBuilt) {
      throw new UnsupportedOperationException("Can not add data to an already built builder");
    }
    return new FrameSequenceBuilder(this, stringConstantPool, frameConstantPool, stackConstantPool);
  }

  public MLTChunk build(long duration) {
    try {
      return new MLTChunk(
          startTs,
          duration,
          threadId,
          threadName,
          stringConstantPool,
          frameConstantPool,
          stackConstantPool,
          stacks);
    } finally {
      isBuilt = true;
    }
  }

  MLTChunkBuilder addFrameSequence(FrameSequence frameSequence) {
    stacks.add(frameSequence);
    return this;
  }
}
