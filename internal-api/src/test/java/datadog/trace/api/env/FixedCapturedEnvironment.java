package datadog.trace.api.env;

import datadog.trace.api.env.CapturedEnvironment.ProcessInfo;
import java.io.File;
import java.util.Map;

/** Helper class that has access to {@link CapturedEnvironment} */
public class FixedCapturedEnvironment {
  private FixedCapturedEnvironment() {}

  /** Load properties instance into the {@code CapturedEnvironment} instance. */
  public static void useFixedEnv(final Map<String, String> properties) {
    CapturedEnvironment.useFixedEnv(properties);
  }

  /** Load process info instance into the {@code CapturedEnvironment} instance. */
  public static void useFixedProcessInfo(final ProcessInfo processInfo) {
    CapturedEnvironment.useFixedProcessInfo(processInfo);
  }

  /** Creates a process info with the given main class and jar file. */
  public static ProcessInfo processInfo(final String mainClass, final File jarFile) {
    return new ProcessInfo(mainClass, jarFile);
  }
}
