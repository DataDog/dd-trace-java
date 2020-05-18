package datadog.trace.core.util;

import java.lang.management.ThreadInfo;
import java.util.List;

public interface ThreadStackProvider {
  List<StackTraceElement[]> getStackTrace(List<Long> threadIds);

  List<ThreadInfo> getThreadInfo(List<Long> threadIds);
}
