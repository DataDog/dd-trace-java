package com.datadog.profiling.utils;

import java.util.Set;

/** Various profiling modes that can be supported by Datadog profiler */
public enum ProfilingMode {
  CPU(1 << 0),
  WALL(1 << 1),
  ALLOCATION(1 << 2),
  MEMLEAK(1 << 3),
  NATIVEMEM(1 << 4),
  NATIVESOCKET(1 << 5),
  /**
   * Tracks the size of the live heap. The Datadog profiler only tracks it as part of live heap
   * profiling, so enabling it also enables live heap profiling, which samples the live objects as
   * {@link #MEMLEAK} does.
   */
  LIVEHEAP_SIZE(1 << 6);

  public final int bitmask;

  ProfilingMode(int bitmask) {
    this.bitmask = bitmask;
  }

  public static int mask(Set<ProfilingMode> modes) {
    int mask = 0;
    for (ProfilingMode mode : modes) {
      mask |= mode.bitmask;
    }
    return mask;
  }
}
