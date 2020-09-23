package datadog.trace.core.util;

import java.util.Collections;
import java.util.List;

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
  public String executeDiagnosticCommand(
      final String command, final Object[] args, final String[] sig) {
    return null;
  }

  @Override
  public List<String> vmArguments() {
    return Collections.emptyList();
  }
}
