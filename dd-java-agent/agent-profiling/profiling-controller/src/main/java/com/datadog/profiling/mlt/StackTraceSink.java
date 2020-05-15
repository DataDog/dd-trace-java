package com.datadog.profiling.mlt;

import java.util.List;

public interface StackTraceSink {
  void dump(String id, List<StackTraceElement[]> stackTraces);
}
