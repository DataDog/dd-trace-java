package datadog.trace.core.util;

import java.util.List;
import java.util.Set;

public interface ThreadStackProvider {
  void getStackTrace(Set<Long> threadIds, List<StackTraceElement[]> stackTraces);
}
