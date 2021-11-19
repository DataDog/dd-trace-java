package com.datadog.profiling.auxiliary;

import java.util.Set;

/** Various profiling modes that can be supported by auxiliary profilers */
public enum ProfilingMode {
  CPU(1),
  WALLCLOCK(2),
  ALLOCATION(4),
  MEMLEAK(8);

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
