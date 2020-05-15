package datadog.trace.core.util;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class NoneThreadStackProvider implements ThreadStackProvider {

  @Override
  public List<StackTraceElement[]> getStackTrace(Set<Long> threadIds) {
    return Collections.emptyList();
  }
}
