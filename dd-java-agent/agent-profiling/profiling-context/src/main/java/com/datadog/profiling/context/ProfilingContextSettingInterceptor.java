package com.datadog.profiling.context;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.api.profiling.TransientProfilingContextHolder;
import java.util.Collection;

final class ProfilingContextSettingInterceptor implements TraceInterceptor {
  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      Collection<? extends MutableSpan> trace) {
    for (MutableSpan span : trace) {
      if (span instanceof TransientProfilingContextHolder) {
        ((TransientProfilingContextHolder) span).storeContextToTag();
      }
    }
    return trace;
  }

  @Override
  public int priority() {
    return System.identityHashCode(ProfilingContextSettingInterceptor.class);
  }
}
