package datadog.trace.api.env;

import java.util.Map;

/** Helper class that has access to {@link CapturedEnvironment} */
public class FixedCapturedEnvironment {
  private FixedCapturedEnvironment() {}

  /** Load properties instance into the {@code CapturedEnvironment} instance. */
  public static void useFixedEnv(final Map<String, String> properties) {
    CapturedEnvironment.useFixedEnv(properties);
  }
}
