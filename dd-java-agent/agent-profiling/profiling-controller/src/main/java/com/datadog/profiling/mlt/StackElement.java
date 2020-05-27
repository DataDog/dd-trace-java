package com.datadog.profiling.mlt;

import lombok.NonNull;

import java.util.Objects;

final class StackElement {
  private int hash;

  private final ConstantPool<FrameElement> framePool;
  private final ConstantPool<StackElement> stackPool;

  private final int headPtr;
  private final int subtreePtr;
  private final int depth;

  StackElement() {
    this.framePool = null;
    this.stackPool = null;
    this.headPtr = -1;
    this.subtreePtr = -1;
    this.depth = 0;
  }

  StackElement(@NonNull FrameElement frame, @NonNull ConstantPool<FrameElement> framePool) {
    this.framePool = framePool;
    this.stackPool = null;
    this.headPtr = framePool.get(frame);
    this.subtreePtr = -1;
    this.depth = 1;
  }

  StackElement(@NonNull FrameElement head, @NonNull StackElement subtree, @NonNull ConstantPool<FrameElement> framePool, @NonNull ConstantPool<StackElement> stackPool) {
    this.framePool = framePool;
    this.stackPool = stackPool;
    this.headPtr = framePool.get(head);
    this.subtreePtr = stackPool.get(subtree);
    this.depth = 1 + subtree.depth;
  }

  public boolean isEmpty() {
    return depth == 0;
  }

  public boolean isSingleFrame() {
    return depth == 1;
  }

  public int getDepth() {
    return depth;
  }

  public FrameElement getHead() {
    return framePool != null ? framePool.get(headPtr) : null;
  }

  public StackElement getSubtree() {
    return stackPool != null ? stackPool.get(subtreePtr) : null;
  }

  int getHeadPtr() {
    return headPtr;
  }

  int getSubtreePtr() {
    return subtreePtr;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    StackElement that = (StackElement) o;
    return headPtr == that.headPtr &&
      subtreePtr == that.subtreePtr &&
      depth == that.depth;
  }

  @Override
  public int hashCode() {
    if (hash == 0) {
      int computedHash = Objects.hash(headPtr, subtreePtr, depth);
      hash = computedHash == 0 ? 1 : computedHash;
    }
    return hash;
  }
}
