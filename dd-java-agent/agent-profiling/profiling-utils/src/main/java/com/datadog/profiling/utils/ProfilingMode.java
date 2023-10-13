package com.datadog.profiling.utils;

import java.util.Set;

/** Various profiling modes that can be supported by Datadog profiler */
public enum ProfilingMode {
  CPU(1 << 0),
  WALL(1 << 1),
  ALLOCATION(1 << 2),
  MEMLEAK(1 << 3);

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
