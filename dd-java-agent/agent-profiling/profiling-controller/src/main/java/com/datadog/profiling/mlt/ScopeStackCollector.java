package com.datadog.profiling.mlt;

import com.datadog.profiling.util.ByteArrayWriter;
import org.eclipse.collections.api.block.procedure.primitive.IntProcedure;
import org.eclipse.collections.api.iterator.IntIterator;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.impl.factory.primitive.IntLists;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import java.util.Objects;
import lombok.Getter;
import lombok.NonNull;

final class ScopeStackCollector {
  private static final int CONSTANT_POOLS_OFFSET = 9;
  private static final int CHUNK_SIZE_OFFSET = 5;
  private static final byte[] MAGIC = {'D', 'D', 0, 9};
  private static final byte VERSION = (byte) 0;

  private final ConstantPool<FrameElement> framePool;
  private final ConstantPool<StackElement> stackPool;
  private final ConstantPool<String> stringPool;
  private final ScopeManager threadStacktraceCollector;

  @Getter
  private final long timestamp;

  @Getter
  private final String scopeId;

  private final MutableIntList stacks = IntLists.mutable.empty();

  ScopeStackCollector(@NonNull String scopeId, ScopeManager threadStacktraceCollector, long timestamp, ConstantPool<String> stringPool, ConstantPool<FrameElement> framePool, ConstantPool<StackElement> stackPool) {
    this.scopeId = scopeId;
    this.framePool = framePool;
    this.stackPool = stackPool;
    this.stringPool = stringPool;
    this.threadStacktraceCollector = threadStacktraceCollector;
    this.timestamp = timestamp;
  }

  public void collect(StackTraceElement[] stackTrace) {
    if (stackTrace.length == 0) {
      return;
    }
    StackElement subtree = null;
    for (StackTraceElement element : stackTrace) {
      subtree = newTree(new FrameElement(element.getClassName(), element.getMethodName(), element.getLineNumber(), stringPool), subtree);
    }
    int stackptr = stackPool.get(subtree);
    addCompressedStackptr(stackptr);
  }

  void addCompressedStackptr(int stackptr) {
    if (!stacks.isEmpty()) {
      int topItem = stacks.removeAtIndex(stacks.size() - 1);
      if (!stacks.isEmpty()) {
        if (topItem < 0) { // topItem is the repetition counter
          if (stacks.getLast() == stackptr && topItem > Integer.MIN_VALUE + 2) {
            /*
             * If inserting a consequent occurrence of the same stack trace and the repetition counter is not
             * overflowing just update the repetition counter.
             */
            stacks.add(topItem - 1);
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
        stacks.add(-1); // insert repetition counter
        return;
      }
    }
    stacks.add(stackptr);
  }

  public byte[] end() {
    return threadStacktraceCollector.endScope(this);
  }

  byte[] serialize() {
    ByteArrayWriter writer = new ByteArrayWriter(16384); // conservatively pre-allocate 16k byte array
    writer
      .writeBytes(MAGIC) // MAGIC
      .writeByte(VERSION) // version
      .writeIntRaw(0) // size; offset = 5
      .writeIntRaw(0) // ptr to constant pools; offset = 9
      .writeLong(timestamp) // start timestamp
      .writeLong(System.nanoTime() - timestamp) // duration
      .writeLong(threadStacktraceCollector.getThreadId());

    IntHashSet stringConstants = IntHashSet.newSetWith(0); // always include ptr 0 (thread name)
    IntHashSet frameConstants = IntHashSet.newSetWith();
    IntHashSet stackConstants = IntHashSet.newSetWith();
    // write out the stack trace sequence and collect the constant pool usage
    stacks.forEach(ptr -> {
      writer.writeInt(ptr);
      if (ptr >= 0) {
        collectStackPtrUsage(ptr, stringConstants, frameConstants, stackConstants);
      }
    });
    writer.writeIntRaw(CONSTANT_POOLS_OFFSET, writer.position()); // write the constant pools offset
    // write constant pool array
    writer.writeInt(stringConstants.size() + 1);
    writer.writeUTF(threadStacktraceCollector.getThreadName()); // 0th CP entry is the thread name
    stringConstants.forEach(ptr -> {
      writer.writeUTF(stringPool.get(ptr));
    });
    // write frame pool array
    writer.writeInt(frameConstants.size());
    frameConstants.forEach(ptr -> {
      FrameElement frame = framePool.get(ptr);
      writer.writeInt(frame.getOwnerPtr()).writeInt(frame.getMethodPtr()).writeInt(frame.getLine());
    });
    // write stack pool array
    writer.writeInt(stackConstants.size());
    stackConstants.forEach(ptr -> {
      StackElement stack = stackPool.get(ptr);
      writer.writeInt(stack.getHeadPtr()).writeInt(stack.getSubtreePtr());
    });
    writer.writeInt(CHUNK_SIZE_OFFSET, writer.position()); // write the chunk size
    return writer.toByteArray();
  }

  private void collectStackPtrUsage(int ptr, IntHashSet stringConstants, IntHashSet frameConstants, IntHashSet stackConstants) {
    if (ptr > -1) {
      StackElement stack = stackPool.get(ptr);
      stackConstants.add(ptr);
      collectFramePtrUsage(stack.getHeadPtr(), stringConstants, frameConstants);
      collectStackPtrUsage(stack.getSubtreePtr(), stringConstants, frameConstants, stackConstants);
    }
  }

  private void collectFramePtrUsage(int ptr, IntHashSet stringConstants, IntHashSet frameConstants) {
    if (ptr > -1) {
      FrameElement frame = framePool.get(ptr);
      frameConstants.add(ptr);
      stringConstants.add(frame.getMethodPtr());
      stringConstants.add(frame.getOwnerPtr());
    }
  }

  private StackElement newTree(FrameElement frame, StackElement subtree) {
    return subtree == null ? new StackElement(frame, framePool) : new StackElement(frame, subtree, framePool, stackPool);
  }

  private void iterateStacks(IntProcedure procedure) {
    iterateStacks(procedure, true);
  }

  private void iterateStacks(IntProcedure procedure, boolean followRepetitions) {
    IntIterator iterator = stacks.intIterator();
    int repeat = 0;
    while (iterator.hasNext()) {
      int element = repeat > 0 ? repeat : iterator.next();

      repeat = iterator.hasNext() ? iterator.next() : 0;

      if (repeat < 0 && followRepetitions) {
        for (int i = 0; i > repeat; i--) {
          procedure.accept(element);
        }
      } else {
        procedure.accept(element);
      }
    }
  }

  void printStacktraces() {
    iterateStacks(this::printSampleStack);
    System.out.println("===> frame pool size: " + framePool.size());
    System.out.println("===> stack pool size: " + stackPool.size());
  }

  private void printSampleStack(int i) {
    System.out.println("===>");
    printSampleStack(stackPool.get(i), 0);
  }

  private void printSampleStack(StackElement stack, int depth) {
    if (stack == null) {
      return;
    }
    for (int j = 0; j < depth; j++) {
      System.out.print(' ');
    }
    FrameElement frame = stack.getHead();
    System.out.println(frame);
    printSampleStack(stack.getSubtree(), depth + 1);
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
