package datadog.trace.civisibility.ci.env;

import datadog.environment.EnvironmentVariables;
import java.util.Map;

public class CiEnvironmentImpl implements CiEnvironment {

  private final Map<String, String> env;

  public CiEnvironmentImpl(Map<String, String> env) {
    this.env = env;
  }

  @SuppressForbidden
  public static CiEnvironment local() {
    return new CiEnvironmentImpl(EnvironmentVariables.getAll());
  }

  @Override
  public String get(String name) {
    return env.get(name);
  }

  @Override
  public Map<String, String> get() {
    return env;
  }
}
