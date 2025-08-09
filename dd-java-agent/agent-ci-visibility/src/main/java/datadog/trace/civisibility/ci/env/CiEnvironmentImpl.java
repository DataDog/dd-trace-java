package datadog.trace.civisibility.ci.env;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Collections;
import java.util.Map;

public class CiEnvironmentImpl implements CiEnvironment {

  private final Map<String, String> env;

  public CiEnvironmentImpl(Map<String, String> env) {
    this.env = env;
  }

  @SuppressForbidden
  public static CiEnvironment local() {
    Map<String, String> env;
    try {
      env = System.getenv();
    } catch (SecurityException e) {
      env = Collections.emptyMap();
    }
    return new CiEnvironmentImpl(env);
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
