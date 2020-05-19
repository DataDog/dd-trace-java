package com.datadog.profiling.mlt;

import java.lang.management.ThreadInfo;

public interface StackTraceSink {
  void write(String[] id, ThreadInfo[] threadInfos);
  byte[] flush();
}
