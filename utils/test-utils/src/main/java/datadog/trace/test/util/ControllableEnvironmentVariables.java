package datadog.trace.test.util;

import datadog.environment.EnvironmentVariables;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.HashMap;
import java.util.Map;

/** Test environment variable provider that replaces the real one while tests run. */
public class ControllableEnvironmentVariables
    extends EnvironmentVariables.EnvironmentVariablesProvider {
  private final Map<String, String> env = new HashMap<>();

  /** Creates a provider seeded with alternating name/value pairs. */
  public ControllableEnvironmentVariables(String... kv) {
    if (kv != null) {
      for (int i = 0; i + 1 < kv.length; i += 2) {
        env.put(kv[i], kv[i + 1]);
      }
    }
  }

  @Override
  public String get(String name) {
    return env.get(name);
  }

  @Override
  public Map<String, String> getAll() {
    return env;
  }

  public void set(String name, String value) {
    env.put(name, value);
  }

  public void removePrefixed(String prefix) {
    env.keySet().removeIf(k -> k.startsWith(prefix));
  }

  public void clear() {
    env.clear();
  }

  /**
   * Installs a new provider as the global {@link EnvironmentVariables} provider. Variables listed
   * in {@code TEST_ENV_PROPAGATE_VARS} (comma-separated) are copied from the real environment.
   */
  @SuppressForbidden
  public static ControllableEnvironmentVariables setup(String... kv) {
    ControllableEnvironmentVariables provider = new ControllableEnvironmentVariables(kv);
    EnvironmentVariables.provider = provider;

    String propagateVars = System.getenv("TEST_ENV_PROPAGATE_VARS");
    if (propagateVars != null) {
      for (String envVar : propagateVars.split(",")) {
        provider.set(envVar, System.getenv(envVar));
      }
    }

    return provider;
  }
}
