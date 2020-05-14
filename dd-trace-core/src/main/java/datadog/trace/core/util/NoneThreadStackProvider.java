package datadog.trace.core.util;

import java.util.List;
import java.util.Set;

public class NoneThreadStackProvider implements ThreadStackProvider {

  @Override
  public void getStackTrace(Set<Long> threadIds, List<StackTraceElement[]> stackTraces) {}
}
