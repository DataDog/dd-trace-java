package com.datadog.profiling.mlt;

import java.util.List;
import java.util.Set;

public class NoneThreadStackProvider implements ThreadStackProvider {

  public static final ThreadStackProvider INSTANCE = new NoneThreadStackProvider();

  @Override
  public void getStackTrace(Set<Long> threadIds, List<StackTraceElement[]> stackTraces) {

  }
}
