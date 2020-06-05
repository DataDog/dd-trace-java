package com.datadog.mlt.io;

import java.util.stream.IntStream;
import java.util.stream.Stream;

/** MLT chunk type interface */
public interface IMLTChunk {
  /** @return chunk format version */
  byte getVersion();

  /** @return chunk start time in epoch (milliseconds since Jan 1, 1970) */
  long getStartTime();

  /** @return chunk duration in milliseconds */
  long getDuration();

  /** @return associated thread ID */
  long getThreadId();

  /** @return associated thread name */
  String getThreadName();

  /** @return the contained {@linkplain FrameSequence}s as an object stream */
  Stream<FrameSequence> frameSequences();

  /** @return the contained {@linkplain FrameSequence}s as their CP index int stream */
  IntStream frameSequenceCpIndexes();

  /** @return the associated {@linkplain String} {@linkplain ConstantPool} */
  ConstantPool<String> getStringPool();

  /** @return the associated {@linkplain FrameElement} {@linkplain ConstantPool} */
  ConstantPool<FrameElement> getFramePool();

  /** @return the associated {@linkplain FrameSequence} {@linkplain ConstantPool} */
  ConstantPool<FrameSequence> getStackPool();

  /**
   * Write out the contents of the chunk in the MLT binary format
   *
   * @return the contents of the chunk in the MLT binary format
   */
  byte[] serialize();

  /**
   * A helper method to expand the compressed version of the {@linkplain FrameSequence} CP index
   * stream. The compressed CP index stream will replace each N subsequent occurrences of the same
   * CP index I with the tuple of (I, N-1)
   *
   * @param cpIndexes the {@linkplain FrameSequence} CP index stream
   * @return decompressed {@linkplain FrameSequence} CP index stream
   */
  static IntStream decompressStackPtrs(IntStream cpIndexes) {
    int[] lastFramePtr = new int[] {-1};
    return cpIndexes.flatMap(
        ptr -> {
          if ((ptr & MLTConstants.EVENT_REPEAT_FLAG) == MLTConstants.EVENT_REPEAT_FLAG) {
            return IntStream.range(0, (ptr & MLTConstants.EVENT_REPEAT_MASK))
                .map(it -> lastFramePtr[0]);
          }
          lastFramePtr[0] = ptr;
          return IntStream.of(ptr);
        });
  }

  /**
   * A helper method to compress the {@linkplain FrameSequence} CP index stream. The compressed CP
   * index stream will replace each N subsequent occurrences of the same CP index I with the tuple
   * of (I, N-1)
   *
   * @param cpIndexes the {@linkplain FrameSequence} CP index stream
   * @return compressed {@linkplain FrameSequence} CP index stream
   */
  static IntStream compressStackPtrs(IntStream cpIndexes) {
    int[] context = new int[] {-1, 0};
    return IntStream.concat(cpIndexes, IntStream.of(Integer.MIN_VALUE))
        .flatMap(
            ptr -> {
              if (ptr == Integer.MIN_VALUE) {
                // synthetic stop element
                if (context[1] > 0) {
                  return IntStream.of(context[1] | MLTConstants.EVENT_REPEAT_FLAG);
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
                return IntStream.of(repeat | MLTConstants.EVENT_REPEAT_FLAG, ptr);
              }
              return IntStream.of(ptr);
            });
  }
}
