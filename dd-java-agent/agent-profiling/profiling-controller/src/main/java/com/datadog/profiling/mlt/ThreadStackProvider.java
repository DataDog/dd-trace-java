package com.datadog.profiling.mlt;

import java.util.List;
import java.util.Set;

public interface ThreadStackProvider {
  void getStackTrace(Set<Long> threadIds, List<StackTraceElement[]> stackTraces);
}
