package com.datadog.profiling.mlt.io;

import static com.datadog.profiling.mlt.io.Constants.EVENT_REPEAT_FLAG;
import static com.datadog.profiling.mlt.io.Constants.EVENT_REPEAT_MASK;

import java.util.stream.IntStream;
import java.util.stream.Stream;

public interface IMLTChunk {
  byte getVersion();

  long getStartTime();

  long getThreadId();

  String getThreadName();

  Stream<FrameStack> stacks();

  IntStream stackPtrs();

  ConstantPool<String> getStringPool();

  ConstantPool<FrameElement> getFramePool();

  ConstantPool<FrameStack> getStackPool();

  byte[] serialize();

  static IntStream decompressStackPtrs(IntStream ptrs) {
    int[] lastFramePtr = new int[] {-1};
    return ptrs.flatMap(
        ptr -> {
          if ((ptr & EVENT_REPEAT_FLAG) == EVENT_REPEAT_FLAG) {
            return IntStream.range(0, (ptr & EVENT_REPEAT_MASK)).map(it -> lastFramePtr[0]);
          }
          lastFramePtr[0] = ptr;
          return IntStream.of(ptr);
        });
  }

  static IntStream compressStackPtrs(IntStream ptrs) {
    int[] context = new int[] {-1, 0};
    return IntStream.concat(ptrs, IntStream.of(Integer.MIN_VALUE))
        .flatMap(
            ptr -> {
              if (ptr == Integer.MIN_VALUE) {
                // synthetic stop element
                if (context[1] > 0) {
                  return IntStream.of(context[1] | EVENT_REPEAT_FLAG);
                }
                return IntStream.empty();
              }
              if (ptr == context[0]) {
                context[1] = context[1] + 1;
                return IntStream.empty();
              }
              context[0] = ptr;
              if (context[1] > 0) {
                int repeat = context[1];
                context[1] = 0;
                return IntStream.of(repeat | EVENT_REPEAT_FLAG, ptr);
              }
              return IntStream.of(ptr);
            });
  }
}
