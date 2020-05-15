package datadog.trace.core.util;

import java.util.List;

public interface ThreadStackProvider {
  List<StackTraceElement[]> getStackTrace(List<Long> threadIds);
}
