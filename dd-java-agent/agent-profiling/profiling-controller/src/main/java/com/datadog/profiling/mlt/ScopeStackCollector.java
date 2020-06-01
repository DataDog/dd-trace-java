package com.datadog.profiling.mlt;

import com.datadog.profiling.mlt.io.ConstantPool;
import com.datadog.profiling.mlt.io.FrameElement;
import com.datadog.profiling.mlt.io.FrameStack;
import com.datadog.profiling.mlt.io.IMLTChunk;
import com.datadog.profiling.mlt.io.MLTWriter;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.NonNull;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.impl.factory.primitive.IntLists;

final class ScopeStackCollector implements IMLTChunk {
  private static final byte VERSION = (byte) 0;

  @Getter private final ConstantPool<FrameElement> framePool;
  @Getter private final ConstantPool<FrameStack> stackPool;
  @Getter private final ConstantPool<String> stringPool;
  private final ScopeManager threadStacktraceCollector;

  @Getter private final long startTime;

  @Getter private final String scopeId;

  private final MutableIntList stacks = IntLists.mutable.empty();

  private final MLTWriter chunkWriter = new MLTWriter();

  ScopeStackCollector(
      @NonNull String scopeId,
      ScopeManager threadStacktraceCollector,
      long startTime,
      ConstantPool<String> stringPool,
      ConstantPool<FrameElement> framePool,
      ConstantPool<FrameStack> stackPool) {
    this.scopeId = scopeId;
    this.framePool = framePool;
    this.stackPool = stackPool;
    this.stringPool = stringPool;
    this.threadStacktraceCollector = threadStacktraceCollector;
    this.startTime = startTime;
  }

  public void collect(StackTraceElement[] stackTrace) {
    if (stackTrace.length == 0) {
      return;
    }
    FrameStack subtree = null;
    for (int i = stackTrace.length - 1; i >= 0; i--) {
      StackTraceElement element = stackTrace[i];
      subtree =
          newTree(
              new FrameElement(
                  element.getClassName(),
                  element.getMethodName(),
                  element.getLineNumber(),
                  stringPool),
              subtree);
    }
    int stackptr = stackPool.get(subtree);
    addCompressedStackptr(stackptr);
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
  public Stream<FrameStack> stacks() {
    // stack pointer stream is internally compressed - needs to be decompressed first
    return IMLTChunk.decompressStackPtrs(stackPtrs()).mapToObj(stackPool::get);
  }

  @Override
  public IntStream stackPtrs() {
    return stacks.primitiveStream();
  }

  @Override
  public byte[] serialize() {
    return chunkWriter.writeChunk(this);
  }

  void addCompressedStackptr(int stackptr) {
    if (!stacks.isEmpty()) {
      int topItem = stacks.removeAtIndex(stacks.size() - 1);
      if (!stacks.isEmpty()) {
        if ((topItem & 0x80000000) == 0x80000000) { // topItem is the repetition counter
          int counter = (topItem & 0x7fffffff);
          if (stacks.getLast() == stackptr && counter < Integer.MAX_VALUE - 2) {
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

  public IMLTChunk end() {
    return threadStacktraceCollector.endScope(this);
  }

  private FrameStack newTree(FrameElement frame, FrameStack subtree) {
    return new FrameStack(frame, subtree, framePool, stackPool);
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
