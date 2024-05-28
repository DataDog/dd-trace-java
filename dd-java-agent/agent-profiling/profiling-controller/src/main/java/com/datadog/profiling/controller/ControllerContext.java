package com.datadog.profiling.controller;

import com.datadog.profiling.utils.ProfilingMode;
import java.util.EnumSet;
import java.util.Set;

/**
 * The controller context is for accumulating information about controllers which provides a simple
 * protocol for the controllers to communicate with each other. This is necessary because the
 * controllers are not actually self-contained, e.g. some JFR events are mutually exclusive with the
 * data collected by DDProf.
 */
public class ControllerContext {

  private boolean isDatadogProfilerEnabled;
  private String datadogProfilerUnavailableReason;
  private Set<ProfilingMode> datadogProfilingModes = EnumSet.noneOf(ProfilingMode.class);

  public ControllerContext setDatadogProfilerEnabled(boolean datadogProfilerActive) {
    isDatadogProfilerEnabled = datadogProfilerActive;
    return this;
  }

  public ControllerContext setDatadogProfilingModes(Set<ProfilingMode> datadogProfilingModes) {
    this.datadogProfilingModes = datadogProfilingModes;
    return this;
  }

  public ControllerContext setDatadogProfilerUnavailableReason(
      String datadogProfilerUnavailableReason) {
    this.datadogProfilerUnavailableReason = datadogProfilerUnavailableReason;
    return this;
  }

  /**
   * A snapshot is an immutable copy of the context (state shared between controllers)
   *
   * @return an immutable snapshot of the context
   */
  public Snapshot snapshot() {
    return new Snapshot(this);
  }

  public static final class Snapshot {
    private final boolean isDatadogProfilerEnabled;
    private final String datadogProfilerUnavailableReason;
    private final Set<ProfilingMode> datadogProfilingModes;

    public Snapshot(ControllerContext context) {
      this(
          context.isDatadogProfilerEnabled,
          EnumSet.copyOf(context.datadogProfilingModes),
          context.datadogProfilerUnavailableReason);
    }

    private Snapshot(
        boolean isDatadogProfilerEnabled,
        Set<ProfilingMode> datadogProfilingModes,
        String datadogProfilerFailureReason) {
      this.isDatadogProfilerEnabled = isDatadogProfilerEnabled;
      this.datadogProfilingModes = datadogProfilingModes;
      this.datadogProfilerUnavailableReason = datadogProfilerFailureReason;
    }

    public boolean isDatadogProfilerEnabled() {
      return isDatadogProfilerEnabled;
    }

    public Set<ProfilingMode> getDatadogProfilingModes() {
      return datadogProfilingModes;
    }

    public String getDatadogProfilerUnavailableReason() {
      return datadogProfilerUnavailableReason;
    }
  }
}
