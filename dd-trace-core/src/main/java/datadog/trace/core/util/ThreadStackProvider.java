package datadog.trace.core.util;

import java.lang.management.ThreadInfo;

public interface ThreadStackProvider {
  StackTraceElement[][] EMPTY_STACKTRACE_ARRAY = new StackTraceElement[0][];

  ThreadInfo[] EMPTY_THERADINFO_ARRAY = new ThreadInfo[0];

  StackTraceElement[][] getStackTrace(long[] threadIds);

  ThreadInfo[] getThreadInfo(long[] threadIds);
}
