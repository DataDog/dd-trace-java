package com.datadog.profiling.context;

public final class JfrTimestampPatch {
  public static void execute(ClassLoader agentClassLoader) {
    JfrTimestampPatchImpl.execute(agentClassLoader);
  }
}
