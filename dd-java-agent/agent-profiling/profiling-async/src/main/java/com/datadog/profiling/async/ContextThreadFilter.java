package com.datadog.profiling.async;

import datadog.trace.bootstrap.instrumentation.api.ContextThreadListener;

/**
 * This class must be installed early to be able to see all scope initialisations, which means it
 * must not be modified to depend on JFR, so that it can be installed before tracing starts.
 */
public class ContextThreadFilter implements ContextThreadListener {

  @Override
  public void onAttach() {
    if (AsyncProfilerConfig.isWallThreadFilterEnabled()) {
      AsyncProfiler.getInstance().addCurrentThread();
    }
  }

  @Override
  public void onDetach() {
    if (AsyncProfilerConfig.isWallThreadFilterEnabled()) {
      AsyncProfiler.getInstance().removeCurrentThread();
    }
  }
}
