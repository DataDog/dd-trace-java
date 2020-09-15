package datadog.trace.core.util;

public class NoneSystemProvider implements SystemProvider {
  @Override
  public long getThreadCpuTime() {
    return Long.MIN_VALUE;
  }

  @Override
  public int getCurrentPid() {
    return 0;
  }
}
