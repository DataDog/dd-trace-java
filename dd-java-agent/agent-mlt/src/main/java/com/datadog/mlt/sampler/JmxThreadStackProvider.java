package com.datadog.mlt.sampler;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

final class JmxThreadStackProvider implements ThreadStackProvider {
  private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

  public static final ThreadStackProvider INSTANCE = new JmxThreadStackProvider();

  @Override
  public StackTraceElement[][] getStackTrace(long[] threadIds) {
    // TODO maxDepth should be configurable
    ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadIds, 128);
    if (threadInfos.length == 0) {
      return EMPTY_STACKTRACE_ARRAY;
    }
    StackTraceElement[][] stackTraces = new StackTraceElement[threadInfos.length][];
    for (int i = 0; i < threadInfos.length; i++) {
      stackTraces[i] = threadInfos[i].getStackTrace();
    }
    return stackTraces;
  }

  @Override
  public ThreadInfo[] getThreadInfo(long[] threadIds) {
    // TODO maxDepth should be configurable
    ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadIds, 128);
    if (threadInfos.length == 0) {
      return EMPTY_THERADINFO_ARRAY;
    }
    return threadInfos;
  }
}
