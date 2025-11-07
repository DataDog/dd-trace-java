package datadog.trace.agent.tooling.profiler;

import de.thetaphi.forbiddenapis.SuppressForbidden;

public final class EnvironmentChecker {
  @SuppressForbidden
  public static boolean checkEnvironment(String temp) {
    StringBuilder builder = new StringBuilder();
    // forward the functionality to the core profiling env checker class
    boolean rslt =
        com.datadog.profiling.controller.EnvironmentChecker.checkEnvironment(temp, builder);
    System.out.println(builder);
    return rslt;
  }
}
