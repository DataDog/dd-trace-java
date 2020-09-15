package datadog.trace.core.util;

public class NoneSystemAccessProvider implements SystemAccessProvider {
  @Override
  public long getThreadCpuTime() {
    return Long.MIN_VALUE;
  }

  @Override
  public int getCurrentPid() {
    return 0;
  }
}
