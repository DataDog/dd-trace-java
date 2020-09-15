package datadog.trace.core.util;

final class NoneSystemAccessProvider implements SystemAccessProvider {
  @Override
  public long getThreadCpuTime() {
    return Long.MIN_VALUE;
  }

  @Override
  public int getCurrentPid() {
    return 0;
  }

  @Override
  public String executeDiagnosticCommand(String command, Object[] args, String[] sig) {
    return null;
  }
}
