package datadog.trace.agent.tooling;

import com.datadog.profiling.agent.ProfilingAgent;

public class ProfilerInstaller {
  public static void installProfiler() {
    try {
      System.out.println("===> Install Profiler");
      ProfilingAgent.run(true, null);
      System.out.println("===> Profiler started");
    } catch (Exception e) {
      e.printStackTrace(System.out);
    }
  }
}
