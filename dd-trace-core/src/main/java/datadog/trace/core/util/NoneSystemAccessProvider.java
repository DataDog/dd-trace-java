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
    return "Not executed, JMX not initialized.";
  }

  @Override
  public List<String> getVMArguments() {
    return Collections.emptyList();
  }
}
