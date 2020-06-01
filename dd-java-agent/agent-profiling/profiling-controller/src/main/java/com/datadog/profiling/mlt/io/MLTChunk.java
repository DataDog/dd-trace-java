package com.datadog.profiling.mlt.io;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Generated;
import lombok.ToString;

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
  @ToString.Exclude @EqualsAndHashCode.Exclude private final ConstantPool<FrameStack> stackPool;

  private final List<FrameStack> stacks;

  @ToString.Exclude @EqualsAndHashCode.Exclude private final MLTWriter writer = new MLTWriter();

  @Generated // disable jacoco check; the method is trivial
  @Override
  public Stream<FrameStack> stacks() {
    return stacks.stream();
  }

  // disable jacoco check; the method is trivial
  @Override
  public IntStream stackPtrs() {
    return IMLTChunk.compressStackPtrs(stacks.stream().mapToInt(FrameStack::getPtr));
  }

  // disable jacoco check; the method is trivial
  @Override
  public byte[] serialize() {
    return writer.writeChunk(this);
  }
}
