package com.datadog.profiling.utils.zstd;

/**
 * Interface for ASM-generated match-copy operations.
 *
 * <p>Must be public for visibility to dynamically generated classes loaded via AsmClassLoader.
 */
public interface MatchCopy {
  void copy(byte[] dst, long dstOffset, byte[] src, long srcOffset, int length);
}
