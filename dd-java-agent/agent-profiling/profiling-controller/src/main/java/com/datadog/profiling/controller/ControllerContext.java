package com.datadog.profiling.controller;

import com.datadog.profiling.utils.ProfilingMode;
import java.util.EnumSet;
import java.util.Set;

public class ControllerContext {

  private boolean isDatadogProfilerActive;
  private Set<ProfilingMode> datadogProfilingModes = EnumSet.noneOf(ProfilingMode.class);

  public boolean isDatadogProfilerActive() {
    return isDatadogProfilerActive;
  }

  public void setDatadogProfilerActive(boolean datadogProfilerActive) {
    isDatadogProfilerActive = datadogProfilerActive;
  }

  public Set<ProfilingMode> getDatadogProfilingModes() {
    return datadogProfilingModes;
  }

  public void setDatadogProfilingModes(Set<ProfilingMode> datadogProfilingModes) {
    this.datadogProfilingModes = datadogProfilingModes;
  }

  public Snapshot snapshot() {
    return new Snapshot(this);
  }

  public static final class Snapshot {
    private final boolean isDatadogProfilerActive;
    private final Set<ProfilingMode> datadogProfilingModes;

    public Snapshot(ControllerContext context) {
      this(context.isDatadogProfilerActive, EnumSet.copyOf(context.datadogProfilingModes));
    }

    private Snapshot(boolean isDatadogProfilerActive, Set<ProfilingMode> datadogProfilingModes) {
      this.isDatadogProfilerActive = isDatadogProfilerActive;
      this.datadogProfilingModes = datadogProfilingModes;
    }

    public boolean isDatadogProfilerActive() {
      return isDatadogProfilerActive;
    }

    public Set<ProfilingMode> getDatadogProfilingModes() {
      return datadogProfilingModes;
    }
  }
}
