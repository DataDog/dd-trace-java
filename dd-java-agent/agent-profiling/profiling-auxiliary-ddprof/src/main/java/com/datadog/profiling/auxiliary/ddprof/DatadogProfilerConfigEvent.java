package com.datadog.profiling.auxiliary.ddprof;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

@Name("datadog.DatadogProfilerConfig")
@Label("Datadog Profiler Configuration")
@Description("The active Datadog profiler configuration")
@Category("Datadog")
@Period("endChunk")
@StackTrace(false)
public class DatadogProfilerConfigEvent extends Event {
  @Label("CPU Sampling Interval")
  @Description(
      "Number of milliseconds used by a CPU between two subsequent samples or -1 if inactive")
  @Timespan("MILLISECONDS")
  private final long cpuInterval;

  @Label("Wall Sampling Interval")
  @Description(
      "Number of milliseconds used by a Wall between two subsequent samples or -1 if inactive")
  @Timespan("MILLISECONDS")
  private final long wallInterval;

  @Label("Allocation Sampling Interval")
  @Description("Number of bytes allocated between two subsequent samples or -1 if inactive")
  @DataAmount
  private final long allocInterval;

  @Label("MemLeak Sampling Interval")
  @Description("Number of bytes allocated between two subsequent samples or -1 if inactive")
  @DataAmount
  private final long memleakInterval;

  @Label("MemLeak Sampling Capacity")
  @Description("Number of objects to track")
  private final long memleakCapacity;

  @Label("Profiling Mode")
  @Description("Profiling mode bitmask")
  private final int modeMask;

  @Label("Version")
  @Description("Datadog profiler version string")
  private final String version;

  @Label("Library Path")
  @Description("Path to Datadog profiler library or null")
  private final String libPath;

  public DatadogProfilerConfigEvent(
      String version,
      String libPath,
      long cpuInterval,
      long wallInterval,
      long allocInterval,
      long memleakInterval,
      long memleakCapacity,
      int modeMask) {
    this.version = version;
    this.libPath = libPath;
    this.cpuInterval = cpuInterval;
    this.wallInterval = wallInterval;
    this.allocInterval = allocInterval;
    this.memleakInterval = memleakInterval;
    this.memleakCapacity = memleakCapacity;
    this.modeMask = modeMask;
  }
}
