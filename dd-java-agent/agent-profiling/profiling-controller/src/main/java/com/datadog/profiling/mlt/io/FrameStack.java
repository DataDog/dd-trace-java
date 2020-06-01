package com.datadog.profiling.mlt.io;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Generated;
import lombok.NonNull;

public final class FrameStack {
  private int stackPtr = -1;
  private int hash;

  private final ConstantPool<FrameElement> framePool;
  private final ConstantPool<FrameStack> stackPool;

  private final int[] framePtrs;
  private final int subtreePtr;

  @Generated // do not force unit tests for lombok generated null checks
  public FrameStack(
      int stackPtr,
      @NonNull int[] framePtrs,
      int subtreePtr,
      @NonNull ConstantPool<FrameElement> framePool,
      @NonNull ConstantPool<FrameStack> stackPool) {
    if (framePtrs.length == 0 && subtreePtr != -1) {
      throw new IllegalArgumentException();
    }

    this.stackPtr = stackPtr;
    this.framePool = framePool;
    this.stackPool = stackPool;
    this.framePtrs = Arrays.copyOf(framePtrs, framePtrs.length);
    this.subtreePtr = subtreePtr;
  }

  @Generated // do not force unit tests for lombok generated null checks
  public FrameStack(
      @NonNull FrameElement head,
      FrameStack subtree,
      @NonNull ConstantPool<FrameElement> framePool,
      @NonNull ConstantPool<FrameStack> stackPool) {
    this.framePool = framePool;
    this.stackPool = stackPool;
    this.framePtrs = new int[] {framePool.get(head)};
    this.subtreePtr = stackPool.get(subtree);
  }

  public Stream<FrameElement> frames() {
    return framePtrs().mapToObj(framePool::get);
  }

  int getPtr() {
    if (stackPtr == -1) {
      stackPtr = stackPool.get(this);
    }
    return stackPtr;
  }

  int depth() {
    if (isEmpty()) {
      return 0;
    }
    if (isLeaf()) {
      return framePtrs.length;
    }

    return framePtrs.length + stackPool.get(subtreePtr).depth();
  }

  int getHeadPtr() {
    return framePtrs.length > 0 ? framePtrs[0] : -1;
  }

  int getSubtreePtr() {
    return subtreePtr;
  }

  private boolean isEmpty() {
    return framePtrs.length == 0 && subtreePtr == -1;
  }

  private boolean isLeaf() {
    return framePtrs.length <= 0 || subtreePtr == -1;
  }

  private IntStream framePtrs() {
    if (isEmpty()) {
      return IntStream.empty();
    }
    IntStream frameStream = Arrays.stream(framePtrs);
    if (isLeaf()) {
      return frameStream;
    }

    return IntStream.concat(frameStream, stackPool.get(subtreePtr).framePtrs());
  }

  @Generated // exclude from jacoco; EqualsVerifier gets confused with the cached hash value and can
             // not be used
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    FrameStack that = (FrameStack) o;
    return Arrays.equals(framePtrs, that.framePtrs) && subtreePtr == that.subtreePtr;
  }

  @Generated // exclude from jacoco; EqualsVerifier gets confused with the cached hash value and can
             // not be used
  @Override
  public int hashCode() {
    if (hash == 0) {
      int computedHash = 1;
      computedHash = computedHash * 31 + Arrays.hashCode(framePtrs);
      computedHash = computedHash * 31 + subtreePtr;
      hash = computedHash == 0 ? 1 : computedHash;
    }
    return hash;
  }

  @Generated // exclude from jacoco
  @Override
  public String toString() {
    return frames().map(FrameElement::toString).collect(Collectors.joining(","));
  }
}
