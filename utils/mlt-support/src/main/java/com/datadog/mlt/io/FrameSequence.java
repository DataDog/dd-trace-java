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
  }

  /**
   * Retrieve the sequence elements stream
   *
   * @return the sequence {@linkplain FrameElement} instances iterated in-order as a stream
   */
  public Stream<FrameElement> frames() {
    return frameCpIndexes().mapToObj(framePool::get);
  }

  public int length() {
    if (isEmpty()) {
      return 0;
    }
    if (isLeaf()) {
      return frameCpIndexes.length;
    }

    return frameCpIndexes.length + stackPool.get(subsequenceCpIndex).length();
  }

  int getCpIndex() {
    if (cpIndex == -1) {
      cpIndex = stackPool.getOrInsert(this);
    }
    return cpIndex;
  }

  int getHeadCpIndex() {
    return frameCpIndexes.length > 0 ? frameCpIndexes[0] : -1;
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

  private IntStream frameCpIndexes() {
    if (isEmpty()) {
      return IntStream.empty();
    }
    IntStream frameStream = Arrays.stream(frameCpIndexes);
    if (isLeaf()) {
      return frameStream;
    }

    return IntStream.concat(frameStream, stackPool.get(subsequenceCpIndex).frameCpIndexes());
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
    return frames().map(FrameElement::toString).collect(Collectors.joining(","));
  }
}
