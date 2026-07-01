package datadog.trace.agent.tooling;

import com.datadog.profiling.agent.ProfilingAgent;
import datadog.trace.api.Config;

public class ProfilerInstaller {
  public static boolean installProfiler() {
    if (Config.get().isProfilingEnabled()) {
      try {
        ProfilingAgent.run(true, null);
        return true;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    return false;
  }
}
