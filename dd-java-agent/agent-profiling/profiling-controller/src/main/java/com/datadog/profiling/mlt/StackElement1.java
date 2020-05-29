package com.datadog.profiling.mlt;

import lombok.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class StackElement1 {
  private final int[] framePtrs;
  private final int subtreePtr;
  private final ConstantPool<FrameElement> framePool;
  private final ConstantPool<StackElement1> stackPool;

  public StackElement1(@NonNull int[] framePtrs, int subtreePtr, @NonNull ConstantPool<FrameElement> framePool, @NonNull ConstantPool<StackElement1> stackPool) {
    this.framePtrs = Arrays.copyOf(framePtrs, framePtrs.length);
    this.subtreePtr = subtreePtr;
    this.framePool = framePool;
    this.stackPool = stackPool;
  }

  public List<FrameElement> getFrames() {
    List<FrameElement> elements = new ArrayList<>(framePtrs.length);
    for (int framePtr : framePtrs) {
      elements.add(framePool.get(framePtr));
    }
    if (subtreePtr > -1) {
      StackElement1 stack = stackPool.get(subtreePtr);
      elements.addAll(stack.getFrames());
    }
    return elements;
  }

  @Override
  public String toString() {
    return Arrays.toString(getFrames().toArray());
  }
}
