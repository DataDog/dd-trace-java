package com.datadog.profiling.mlt;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Set;

public class JmxThreadStackProvider implements ThreadStackProvider {
  private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

  public static final ThreadStackProvider INSTANCE = new JmxThreadStackProvider();

  @Override
  public void getStackTrace(Set<Long> threadIds, List<StackTraceElement[]> stackTraces) {
    long[] ids = new long[threadIds.size()];
    int idx = 0;
    for (Long id : threadIds) {
      ids[idx] = id;
      idx++;
    }
    ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(ids); // maxDepth?
    for (int i = 0; i < threadInfos.length; i++) {
      stackTraces.add(threadInfos[i].getStackTrace());
    }
  }
}
