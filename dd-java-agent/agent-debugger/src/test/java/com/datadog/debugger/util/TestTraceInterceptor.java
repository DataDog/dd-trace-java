package com.datadog.debugger.util;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class TestTraceInterceptor implements TraceInterceptor {
  private List<? extends MutableSpan> currentTrace;
  private List<List<? extends MutableSpan>> allTraces = new ArrayList<>();

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      Collection<? extends MutableSpan> trace) {
    currentTrace = new ArrayList<>(trace);
    allTraces.add(new ArrayList<>(trace));
    return trace;
  }

  @Override
  public int priority() {
    return 0;
  }

  public List<? extends MutableSpan> getTrace() {
    return currentTrace;
  }

  public MutableSpan getFirstSpan() {
    if (currentTrace == null) {
      return null;
    }
    return currentTrace.iterator().next();
  }

  public List<List<? extends MutableSpan>> getAllTraces() {
    return allTraces;
  }
}
