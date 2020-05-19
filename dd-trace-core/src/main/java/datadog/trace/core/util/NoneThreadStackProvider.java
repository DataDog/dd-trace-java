package datadog.trace.core.util;

import java.lang.management.ThreadInfo;

public class NoneThreadStackProvider implements ThreadStackProvider {

  @Override
  public StackTraceElement[][] getStackTrace(long[] threadIds) {
    return ThreadStackProvider.EMPTY_STACKTRACE_ARRAY;
  }

  @Override
  public ThreadInfo[] getThreadInfo(long[] threadIds) {
    return ThreadStackProvider.EMPTY_THERADINFO_ARRAY;
  }
}
