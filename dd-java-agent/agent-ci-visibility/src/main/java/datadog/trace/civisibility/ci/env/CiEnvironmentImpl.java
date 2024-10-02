package datadog.trace.civisibility.ci.env;

import java.util.Map;

public class CiEnvironmentImpl implements CiEnvironment {

  private final Map<String, String> env;

  public CiEnvironmentImpl(Map<String, String> env) {
    this.env = env;
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
