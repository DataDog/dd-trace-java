package com.datadog.profiling.utils.zstd;

/** Interface for ASM-generated match-copy operations. */
public interface MatchCopy {
  void copy(byte[] dst, long dstOffset, byte[] src, long srcOffset, int length);
}
