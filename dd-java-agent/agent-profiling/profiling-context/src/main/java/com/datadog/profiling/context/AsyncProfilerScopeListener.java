package com.datadog.profiling.context;

import com.datadog.profiling.async.AsyncProfiler;
import datadog.trace.api.DDId;
import datadog.trace.api.scopemanager.ExtendedScopeListener;

public class AsyncProfilerScopeListener implements ExtendedScopeListener {
  // constructed by reflection so do not make construtor private
  private static final AsyncProfiler ASYNC_PROFILER = AsyncProfiler.getInstance();

  @Override
  public void afterScopeActivated() {}

  @Override
  public void afterScopeActivated(DDId traceId, DDId localRootSpanId, DDId spanId) {
    if (ASYNC_PROFILER.isAvailable()) {
      ASYNC_PROFILER.setContext(localRootSpanId.toLong(), spanId.toLong());
    }
  }

  @Override
  public void afterScopeClosed() {
    // this operation is basically free, but we should still try to eliminate it by evolving
    // the scope listener protocol
    if (ASYNC_PROFILER.isAvailable()) {
      ASYNC_PROFILER.clearContext();
    }
  }
}
