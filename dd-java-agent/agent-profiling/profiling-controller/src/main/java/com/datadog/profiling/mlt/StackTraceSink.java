package com.datadog.profiling.mlt;

import java.lang.management.ThreadInfo;
import java.util.List;

public interface StackTraceSink {
  void dump(String id, List<ThreadInfo> threadInfos);
}
