package com.datadog.mlt.sampler;

import com.datadog.mlt.io.ConstantPool;
import com.datadog.mlt.io.FrameElement;
import com.datadog.mlt.io.FrameSequence;
import com.datadog.mlt.io.IMLTChunk;
import com.datadog.mlt.io.MLTWriter;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class ScopeStackCollector implements IMLTChunk {
  /*
   * TODO seems like subtree compression is worse than plain full-tree deduplication
   *  when the subtree compression is finally removed this flag should go as well + subtree support in FrameSequence
   */
  private static final boolean USE_SUBTREE_COMPRESSION =
      Boolean.getBoolean("mlt.subtree_compression");

  private static final byte VERSION = (byte) 0;

  @Getter private final ConstantPool<FrameElement> framePool;
  @Getter private final ConstantPool<FrameSequence> stackPool;
  @Getter private final ConstantPool<String> stringPool;
  private final ScopeManager threadStacktraceCollector;

  @Getter private final long startTime;
  private final long startTimeNs;

  @Getter private final String scopeId;

  private final IntList stacks = new IntArrayList();

  private final MLTWriter chunkWriter = new MLTWriter();

  ScopeStackCollector(
      @NonNull String scopeId,
      ScopeManager threadStacktraceCollector,
      long startTimeNs,
      long startTimeEpoch,
      ConstantPool<String> stringPool,
      ConstantPool<FrameElement> framePool,
      ConstantPool<FrameSequence> stackPool) {
    this.scopeId = scopeId;
    this.framePool = framePool;
    this.stackPool = stackPool;
    this.stringPool = stringPool;
    this.threadStacktraceCollector = threadStacktraceCollector;
    this.startTime = startTimeEpoch;
    this.startTimeNs = startTimeNs;

    // Capture the base stacktrace:
    collect(new Throwable().getStackTrace());
  }

  public void collect(StackTraceElement[] stackTrace) {
    if (stackTrace.length == 0) {
      return;
    }
    FrameSequence tree = null;
    if (USE_SUBTREE_COMPRESSION) {
      for (int i = stackTrace.length - 1; i >= 0; i--) {
        StackTraceElement element = stackTrace[i];
        tree =
            newTree(
                new FrameElement(
                    element.getClassName(),
                    element.getMethodName(),
                    element.getLineNumber(),
                    stringPool,
                    framePool),
                tree);
      }
    } else {
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
    }

    addCompressedStackptr(tree.getCpIndex());
  }

  @Override
  public long getDuration() {
    return TimeUnit.MILLISECONDS.convert(System.nanoTime() - startTimeNs, TimeUnit.NANOSECONDS);
  }

  @Override
  public byte getVersion() {
    return VERSION;
  }

  @Override
  public long getThreadId() {
    return threadStacktraceCollector.getThreadId();
  }

  @Override
  public String getThreadName() {
    return threadStacktraceCollector.getThreadName();
  }

  @Override
  public boolean hasStacks() {
    return !stacks.isEmpty();
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
    return chunkWriter.writeChunk(this);
  }

  @Override
  public void serialize(Consumer<ByteBuffer> consumer) {
    chunkWriter.writeChunk(this, consumer);
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

  public <T> T end(Function<IMLTChunk, T> onEnd) {
    return onEnd.apply(threadStacktraceCollector.endScope(this));
  }

  private FrameSequence newTree(FrameElement frame, FrameSequence subtree) {
    return new FrameSequence(frame, subtree, framePool, stackPool);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ScopeStackCollector that = (ScopeStackCollector) o;
    return scopeId.equals(that.scopeId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(scopeId);
  }
}
