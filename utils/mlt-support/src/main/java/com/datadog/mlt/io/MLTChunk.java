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
  private final int size;
  private final long startTime;
  private final long duration;
  private final long threadId;
  private final String threadName;
  @ToString.Exclude @EqualsAndHashCode.Exclude private final ConstantPool<String> stringPool;
  @ToString.Exclude @EqualsAndHashCode.Exclude private final ConstantPool<FrameElement> framePool;
  @ToString.Exclude @EqualsAndHashCode.Exclude private final ConstantPool<FrameSequence> stackPool;

  private final List<FrameSequence> stacks;

  @ToString.Exclude @EqualsAndHashCode.Exclude private final MLTWriter writer = new MLTWriter();

  @Generated // disable jacoco check; the method is trivial
  @Override
  public boolean hasStacks() {
    return !stacks.isEmpty();
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
    return writer.writeChunk(this);
  }

  @Override
  public void serialize(Consumer<ByteBuffer> consumer) {
    writer.writeChunk(this, consumer);
  }
}
