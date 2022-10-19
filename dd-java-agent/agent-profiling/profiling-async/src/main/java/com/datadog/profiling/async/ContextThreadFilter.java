package com.datadog.profiling.async;

import datadog.trace.bootstrap.instrumentation.api.ContextThreadListener;

public class ContextThreadFilter implements ContextThreadListener {

  private ContextThreadFilter() {}

  @Override
  public void onAttach() {
    AsyncProfiler.getInstance().addCurrentThread();
  }

  @Override
  public void onDetach() {
    AsyncProfiler.getInstance().removeCurrentThread();
  }
}
