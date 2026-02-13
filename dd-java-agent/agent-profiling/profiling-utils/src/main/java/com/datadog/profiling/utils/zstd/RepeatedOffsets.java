package com.datadog.profiling.utils.zstd;

/** Tracks the three repeated-offset state values used in zstd sequence encoding. */
final class RepeatedOffsets {
  private int offset0 = 1;
  private int offset1 = 4;

  private int tempOffset0;
  private int tempOffset1;

  int getOffset0() {
    return offset0;
  }

  int getOffset1() {
    return offset1;
  }

  void saveOffset0(int offset) {
    tempOffset0 = offset;
  }

  void saveOffset1(int offset) {
    tempOffset1 = offset;
  }

  void commit() {
    offset0 = tempOffset0;
    offset1 = tempOffset1;
  }
}
