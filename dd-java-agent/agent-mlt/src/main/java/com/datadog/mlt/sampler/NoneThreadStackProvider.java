package com.datadog.mlt.sampler;

import java.lang.management.ThreadInfo;

final class NoneThreadStackProvider implements ThreadStackProvider {

  @Override
  public StackTraceElement[][] getStackTrace(long[] threadIds) {
    return EMPTY_STACKTRACE_ARRAY;
  }

  @Override
  public ThreadInfo[] getThreadInfo(long[] threadIds) {
    return EMPTY_THERADINFO_ARRAY;
  }
}
