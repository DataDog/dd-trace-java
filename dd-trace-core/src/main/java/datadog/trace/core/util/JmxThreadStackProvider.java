package datadog.trace.core.util;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

public class JmxThreadStackProvider implements ThreadStackProvider {
  private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

  public static final ThreadStackProvider INSTANCE = new JmxThreadStackProvider();

  @Override
  public StackTraceElement[][] getStackTrace(long[] threadIds) {
    ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadIds); // maxDepth?
    if (threadInfos.length == 0) {
      return ThreadStackProvider.EMPTY_STACKTRACE_ARRAY;
    }
    StackTraceElement[][] stackTraces = new StackTraceElement[threadInfos.length][];
    for (int i = 0; i < threadInfos.length; i++) {
      stackTraces[i] = threadInfos[i].getStackTrace();
    }
    return stackTraces;
  }

  @Override
  public ThreadInfo[] getThreadInfo(long[] threadIds) {
    ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadIds); // maxDepth?
    if (threadInfos.length == 0) {
      return ThreadStackProvider.EMPTY_THERADINFO_ARRAY;
    }
    return threadInfos;
  }
}
