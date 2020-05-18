package datadog.trace.core.util;

import java.lang.management.ThreadInfo;
import java.util.Collections;
import java.util.List;

public class NoneThreadStackProvider implements ThreadStackProvider {

  @Override
  public List<StackTraceElement[]> getStackTrace(List<Long> threadIds) {
    return Collections.emptyList();
  }

  @Override
  public List<ThreadInfo> getThreadInfo(List<Long> threadIds) {
    return Collections.emptyList();
  }
}
