package datadog.trace.agent.tooling;

import com.datadog.profiling.agent.ProfilingAgent;

public class ProfilerInstaller {
  public static void installProfiler() {
    try {
      ProfilingAgent.run(true, null);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
