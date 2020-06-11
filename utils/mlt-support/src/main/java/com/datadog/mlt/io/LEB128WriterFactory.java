package com.datadog.mlt.io;

import java.util.function.Function;

/*
 * TODO The whole class would probably be removed once we pick the implementation we want to use
 */
final class LEB128WriterFactory {
  private static final Function<Integer, LEB128Writer> factoryFunction;

  static {
    /*
     * TODO this will be most likely removed in favor of one of those two implementation
     *  Does not seem worthy of creating a full-fledged config for this.
     */
    String writerType = System.getProperty("mlt.writer", "buffer");
    switch (writerType) {
      case "array":
        {
          factoryFunction = LEB128ByteArrayWriter::new;
          break;
        }
      case "buffer":
        {
          factoryFunction = LEB128ByteBufferWriter::new;
          break;
        }
      default:
        {
          throw new IllegalArgumentException("Unknown writer type: " + writerType);
        }
    }
  }

  static LEB128Writer getWriter(int initialCapacity) {
    return factoryFunction.apply(initialCapacity);
  }
}
