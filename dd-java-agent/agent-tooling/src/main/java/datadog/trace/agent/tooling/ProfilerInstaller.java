package datadog.trace.agent.tooling;

import com.datadog.profiling.agent.ProfilingAgent;
import datadog.trace.api.Config;

public class ProfilerInstaller {
  public static void installProfiler() {
    if (Config.get().isProfilingEnabled()) {
      try {
        ProfilingAgent.run(true, null);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }
}
