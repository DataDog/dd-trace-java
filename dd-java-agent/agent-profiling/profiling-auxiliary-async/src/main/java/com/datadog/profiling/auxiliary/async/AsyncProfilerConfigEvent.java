package com.datadog.profiling.auxiliary.async;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

@Name("datadog.AsyncProfilerConfig")
@Label("Async Profiler Configuration")
@Description("The active async profiler configuration")
@Category("Datadog")
@Period("endChunk")
@StackTrace(false)
public class AsyncProfilerConfigEvent extends Event {
  @Label("CPU Sampling Interval")
  @Description(
      "Number of milliseconds used by a CPU between two subsequent samples or -1 if inactive")
  @Timespan("MILLISECONDS")
  private final long cpuInterval;

  @Label("Allocation Sampling Interval")
  @Description("Number of bytes allocated between two subsequent samples or -1 if inactive")
  @DataAmount
  private final long allocInterval;

  @Label("MemLeak Sampling Interval")
  @Description("Number of bytes allocated between two subsequent samples or -1 if inactive")
  @DataAmount
  private final long memleakInterval;

  @Label("Profiling Mode")
  @Description("Profiling mode bitmask")
  private final int modeMask;

  @Label("Version")
  @Description("Async profiler version string")
  private final String version;

  public AsyncProfilerConfigEvent(
      String version, long cpuInterval, long allocInterval, long memleakInterval, int modeMask) {
    this.version = version;
    this.cpuInterval = cpuInterval;
    this.allocInterval = allocInterval;
    this.memleakInterval = memleakInterval;
    this.modeMask = modeMask;
  }
}
