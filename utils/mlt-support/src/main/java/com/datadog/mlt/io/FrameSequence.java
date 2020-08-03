package com.datadog.mlt.io;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Generated;
import lombok.NonNull;

/** A representation of a stack frame element sequence */
public final class FrameSequence {
  private int cpIndex = -1;
  private int hash;

  private final ConstantPool<FrameElement> framePool;
  private final ConstantPool<FrameSequence> stackPool;

  private final int[] frameCpIndexes;
  private final int subsequenceCpIndex;
  private int length = -1;

  @Generated // do not force unit tests for lombok generated null checks
  FrameSequence(
      int cpIndex,
      @NonNull int[] frameCpIndexes,
      int subsequenceCpIndex,
      @NonNull ConstantPool<FrameElement> framePool,
      @NonNull ConstantPool<FrameSequence> stackPool) {
    if (frameCpIndexes.length == 0 && subsequenceCpIndex != -1) {
      throw new IllegalArgumentException();
    }

    this.cpIndex = cpIndex;
    this.framePool = framePool;
    this.stackPool = stackPool;
    this.frameCpIndexes = Arrays.copyOf(frameCpIndexes, frameCpIndexes.length);
    this.subsequenceCpIndex = subsequenceCpIndex;
    /*
     * If the sequence points to a subsequence `this.length` may not be computed here because the constant pools may not yet be filled up
     */
    if (subsequenceCpIndex == -1) {
      this.length = frameCpIndexes.length;
    }
  }

  @Generated // do not force unit tests for lombok generated null checks
  public FrameSequence(
      @NonNull int[] frameCpIndexes,
      @NonNull ConstantPool<FrameElement> framePool,
      @NonNull ConstantPool<FrameSequence> stackPool) {
    this(-1, frameCpIndexes, -1, framePool, stackPool);
  }

  @Generated // do not force unit tests for lombok generated null checks
  public FrameSequence(
      @NonNull FrameElement head,
      FrameSequence subsequence,
      @NonNull ConstantPool<FrameElement> framePool,
      @NonNull ConstantPool<FrameSequence> stackPool) {
    this.framePool = framePool;
    this.stackPool = stackPool;
    this.frameCpIndexes = new int[] {framePool.getOrInsert(head)};
    this.subsequenceCpIndex = stackPool.getOrInsert(subsequence);
    this.length = 1 + (subsequence != null ? subsequence.length : 0);
  }

  /**
   * Retrieve the sequence elements stream
   *
   * @return the sequence {@linkplain FrameElement} instances iterated in-order as a stream
   */
  public Stream<FrameElement> framesFromLeaves() {
    return frameCpIndexesFromLeafs().mapToObj(framePool::get);
  }

  public Stream<FrameElement> framesFromRoot() {
    return frameCpIndexesFromRoot().mapToObj(framePool::get);
  }

  public int length() {
    if (length == -1) {
      /*
       * the length could not be computed in the constructor - calculate it here and cache the
       * result
       */
      length =
          frameCpIndexes.length
              + (subsequenceCpIndex != -1 ? stackPool.get(subsequenceCpIndex).length() : 0);
    }
    return length;
  }

  public int getCpIndex() {
    if (cpIndex == -1) {
      cpIndex = stackPool.getOrInsert(this);
    }
    return cpIndex;
  }

  int getHeadCpIndex() {
    return frameCpIndexes.length > 0 ? frameCpIndexes[0] : -1;
  }

  int[] getFrameCpIndexes() {
    return frameCpIndexes;
  }

  int getSubsequenceCpIndex() {
    return subsequenceCpIndex;
  }

  private boolean isEmpty() {
    return frameCpIndexes.length == 0 && subsequenceCpIndex == -1;
  }

  private boolean isLeaf() {
    return frameCpIndexes.length <= 0 || subsequenceCpIndex == -1;
  }

  private IntStream frameCpIndexesFromLeafs() {
    if (isEmpty()) {
      return IntStream.empty();
    }
    IntStream frameStream = Arrays.stream(frameCpIndexes);
    if (isLeaf()) {
      return frameStream;
    }

    return subsequenceCpIndex != -1
        ? IntStream.concat(frameStream, stackPool.get(subsequenceCpIndex).frameCpIndexesFromLeafs())
        : frameStream;
  }

  private IntStream frameCpIndexesFromRoot() {
    if (isEmpty()) {
      return IntStream.empty();
    }
    int length = frameCpIndexes.length - 1;
    // Reverse iterate through the array:
    IntStream frameStream = IntStream.rangeClosed(0, length).map(i -> frameCpIndexes[length - i]);
    if (isLeaf()) {
      return frameStream;
    }

    return subsequenceCpIndex != -1
        ? IntStream.concat(stackPool.get(subsequenceCpIndex).frameCpIndexesFromRoot(), frameStream)
        : frameStream;
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
    FrameSequence that = (FrameSequence) o;
    return Arrays.equals(frameCpIndexes, that.frameCpIndexes)
        && subsequenceCpIndex == that.subsequenceCpIndex;
  }

  @Generated // exclude from jacoco; EqualsVerifier gets confused with the cached hash value and can
  // not be used
  @Override
  public int hashCode() {
    if (hash == 0) {
      int computedHash = 1;
      computedHash = computedHash * 31 + Arrays.hashCode(frameCpIndexes);
      computedHash = computedHash * 31 + subsequenceCpIndex;
      hash = computedHash == 0 ? 1 : computedHash;
    }
    return hash;
  }

  @Generated // exclude from jacoco
  @Override
  public String toString() {
    return framesFromLeaves().map(FrameElement::toString).collect(Collectors.joining("->"));
  }
}
