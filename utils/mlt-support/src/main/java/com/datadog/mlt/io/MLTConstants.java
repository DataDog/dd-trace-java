package com.datadog.mlt.io;

/** Shared MLT format constants */
final class MLTConstants {
  static final int CONSTANT_POOLS_OFFSET = 9;
  static final int CHUNK_SIZE_OFFSET = 5;
  static final byte[] MAGIC = {'D', 'D', 0, 9};
  static final int EVENT_REPEAT_FLAG = 0x80000000;
  static final int EVENT_REPEAT_MASK = 0x7fffffff;
}
