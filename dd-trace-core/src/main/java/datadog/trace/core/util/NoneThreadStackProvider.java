package datadog.trace.core.util;

import java.util.Collections;
import java.util.List;

public class NoneThreadStackProvider implements ThreadStackProvider {

  @Override
  public List<StackTraceElement[]> getStackTrace(List<Long> threadIds) {
    return Collections.emptyList();
  }
}
