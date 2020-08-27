package com.datadog.mlt.io;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Getter;

public abstract class MLTChunkCollector implements IMLTChunk {
  /**
   * Base stacktrace to add the first time collect is called. This is done to avoid the cost of
   * converting in the main thread (especially if no additional stacktraces are collected).
   */
  private volatile Throwable baseStack;

  @Getter protected final ConstantPool<FrameElement> framePool;
  @Getter protected final ConstantPool<FrameSequence> stackPool;
  @Getter protected final ConstantPool<String> stringPool;

  private final IntList stacks = new IntArrayList();

  public MLTChunkCollector(
      Throwable baseStack,
      ConstantPool<String> stringPool,
      ConstantPool<FrameElement> framePool,
      ConstantPool<FrameSequence> stackPool) {
    this.baseStack = baseStack;
    this.framePool = framePool;
    this.stackPool = stackPool;
    this.stringPool = stringPool;
  }

  /**
   * Not thread safe. Must not call from multiple threads.
   *
   * @param stackTrace
   */
  public void collect(StackTraceElement[] stackTrace) {
    if (stackTrace.length == 0) {
      return;
    }
    if (baseStack != null) {
      StackTraceElement[] base = baseStack.getStackTrace();
      baseStack = null;
      collect(base); // recursive call to add base as first element.
    }
    FrameSequence tree;
    int[] framePtrs = new int[stackTrace.length];
    for (int i = 0; i < stackTrace.length; i++) {
      StackTraceElement element = stackTrace[i];
      framePtrs[i] =
          framePool.getOrInsert(
              new FrameElement(
                  element.getClassName(),
                  element.getMethodName(),
                  element.getLineNumber(),
                  stringPool,
                  framePool));
    }
    tree = new FrameSequence(framePtrs, framePool, stackPool);

    addCompressedStackptr(tree.getCpIndex());
  }

  @Override
  public boolean hasStacks() {
    // Base stack doesn't count.
    return stacks.size() > 1;
  }

  protected int getStackCount() {
    return stacks.size();
  }

  @Override
  public FrameSequence baseFrameSequence() {
    return stackPool.get(stacks.getInt(0));
  }

  @Override
  public Stream<FrameSequence> frameSequences() {
    // stack pointer stream is internally compressed - needs to be decompressed first
    // Skip over the base stacktrace.
    return IMLTChunk.decompressStackPtrs(frameSequenceCpIndexes().skip(1)).mapToObj(stackPool::get);
  }

  @Override
  public IntStream frameSequenceCpIndexes() {
    int limit = stacks.size();
    IntIterator iterator = stacks.iterator();
    return IntStream.generate(iterator::nextInt).limit(limit);
  }

  @Override
  public byte[] serialize() {
    return MLTWriter.writeChunk(this);
  }

  @Override
  public void serialize(Consumer<ByteBuffer> consumer) {
    MLTWriter.writeChunk(this, consumer);
  }

  void addCompressedStackptr(int stackptr) {
    if (!stacks.isEmpty()) {
      int topItem = stacks.removeInt(stacks.size() - 1);
      if (!stacks.isEmpty()) {
        // checking for compression flag - `(topItem & 0x80000000) == 0x80000000`, simplified as
        // `topItem < 0`
        if (topItem < 0) { // topItem is the repetition counter
          int counter = (topItem & 0x7fffffff);
          if (stacks.getInt(stacks.size() - 1) == stackptr && counter < Integer.MAX_VALUE - 2) {
            /*
             * If inserting a consequent occurrence of the same stack trace and the repetition counter is not
             * overflowing just update the repetition counter.
             */
            stacks.add((counter + 1) | 0x80000000);
            return;
          }
          /*
           * Not inserting a consequent occurrence of the same stack trace or the repetition counter is overflowing
           */
          stacks.add(topItem); // re-insert the topItem
          stacks.add(stackptr);
          return;
        }
      }
      // otherwise the topItem is a plain stack pointer
      if (stackptr == topItem) {
        stacks.add(topItem); // re-insert the topItem
        // inserting a consequent occurrence of the same stack trace
        stacks.add(1 | 0x80000000); // insert repetition counter
        return;
      }
      stacks.add(topItem);
    }
    stacks.add(stackptr);
  }
}
