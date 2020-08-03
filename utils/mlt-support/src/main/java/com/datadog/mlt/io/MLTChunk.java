package com.datadog.mlt.io;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Generated;
import lombok.ToString;

/**
 * A simple, DTO-like {@linkplain IMLTChunk} implementation. Used by {@linkplain MLTReader} to
 * represent the loaded chunk.
 */
@Data
public final class MLTChunk implements IMLTChunk {
  private final byte version;
  @EqualsAndHashCode.Exclude
  private int size;
  private final long startTime;
  private final long duration;
  private final long threadId;
  private final String threadName;
  @ToString.Exclude @EqualsAndHashCode.Exclude private final ConstantPool<String> stringPool;
  @ToString.Exclude @EqualsAndHashCode.Exclude private final ConstantPool<FrameElement> framePool;
  @ToString.Exclude @EqualsAndHashCode.Exclude private final ConstantPool<FrameSequence> stackPool;

  private final List<FrameSequence> stacks;

  public MLTChunk(byte version, int size, long startTime, long duration, long threadId, String threadName, ConstantPool<String> stringPool, ConstantPool<FrameElement> framePool, ConstantPool<FrameSequence> stackPool, List<FrameSequence> stacks) {
    this.version = version;
    this.size = size;
    this.startTime = startTime;
    this.duration = duration;
    this.threadId = threadId;
    this.threadName = threadName;
    this.stringPool = stringPool;
    this.framePool = framePool;
    this.stackPool = stackPool;
    this.stacks = stacks;
  }

  MLTChunk(long startTime, long duration, long threadId, String threadName, ConstantPool<String> stringPool, ConstantPool<FrameElement> framePool, ConstantPool<FrameSequence> stackPool, List<FrameSequence> stacks) {
    this(MLTConstants.VERSION, -1, startTime, duration, threadId, threadName, stringPool, framePool, stackPool, stacks);
    stringPool.insert(0, threadName);
  }

  @Generated // disable jacoco check; the method is trivial
  @Override
  public boolean hasStacks() {
    // Base stack doesn't count.
    return stacks.size() > 1;
  }

  @Override
  public FrameSequence baseFrameSequence() {
    return stacks.get(0);
  }

  @Generated // disable jacoco check; the method is trivial
  @Override
  public Stream<FrameSequence> frameSequences() {
    // Skip the "base" FrameSequence
    return stacks.stream().skip(1);
  }

  // disable jacoco check; the method is trivial
  @Override
  public IntStream frameSequenceCpIndexes() {
    return IMLTChunk.compressStackPtrs(stacks.stream().mapToInt(FrameSequence::getCpIndex));
  }

  // disable jacoco check; the method is trivial
  @Override
  public byte[] serialize() {
    return MLTWriter.writeChunk(this);
  }

  @Override
  public void serialize(Consumer<ByteBuffer> consumer) {
    MLTWriter.writeChunk(this, consumer);
  }

  void adjustSize(int newSize) {
    this.size = newSize;
  }
}
