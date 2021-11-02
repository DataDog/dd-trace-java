package com.datadog.profiling.auxiliary;

/** Various profiling modes that can be supported by auxiliary profilers */
public enum ProfilingMode {
  CPU,
  WALLCLOCK,
  ALLOCATION;
}
