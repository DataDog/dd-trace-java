package datadog.trace.core.util;

import java.util.List;
import java.util.Set;

public interface ThreadStackProvider {
  List<StackTraceElement[]> getStackTrace(Set<Long> threadIds);
}
