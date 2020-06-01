package com.datadog.profiling.mlt.io;

public final class Constants {
  public static final int CONSTANT_POOLS_OFFSET = 9;
  public static final int CHUNK_SIZE_OFFSET = 5;
  public static final byte[] MAGIC = {'D', 'D', 0, 9};
  public static final int EVENT_REPEAT_FLAG = 0x80000000;
  public static final int EVENT_REPEAT_MASK = 0x7fffffff;
}
