package datadog.trace.core.util;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class JmxThreadStackProvider implements ThreadStackProvider {
  private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

  public static final ThreadStackProvider INSTANCE = new JmxThreadStackProvider();

  @Override
  public List<StackTraceElement[]> getStackTrace(List<Long> threadIds) {
    long[] ids = new long[threadIds.size()];
    int idx = 0;
    for (Long id : threadIds) {
      ids[idx] = id;
      idx++;
    }
    ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(ids); // maxDepth?
    if (threadInfos.length == 0) {
      return Collections.emptyList();
    }
    List<StackTraceElement[]> stackTraces = new ArrayList<>();
    for (int i = 0; i < threadInfos.length; i++) {
      stackTraces.add(threadInfos[i].getStackTrace());
    }
    return stackTraces;
  }
}
