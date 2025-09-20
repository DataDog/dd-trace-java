package datadog.trace.test.util

import datadog.environment.EnvironmentVariables

class ControllableEnvironmentVariables extends EnvironmentVariables.EnvironmentVariablesProvider {
  private Map<String, String> env = new HashMap<>()

  ControllableEnvironmentVariables(String... kv) {
    if (kv) {
      for (int i = 0; i + 1 < kv.length; i += 2) {
        env[kv[i]] = kv[i + 1]
      }
    }
  }

  @Override
  String get(String name) {
    return env.get(name)
  }

  @Override
  Map<String, String> getAll() {
    return env
  }

  void set(String name, String value) {
    env.put(name, value)
  }

  void removePrefixed(String prefix) {
    env.keySet().removeAll { k -> k.startsWith(prefix) }
  }

  void clear() {
    env.clear()
  }

  static ControllableEnvironmentVariables setup(String... kv) {
    ControllableEnvironmentVariables provider = new ControllableEnvironmentVariables(kv)
    EnvironmentVariables.provider = provider

    // Propagate specified environment variables to test environment.
    System.getenv("TEST_ENV_PROPAGATE_VARS")
      ?.split(',')
      ?.each { envVar ->
        provider[envVar] = System.getenv(envVar)
      }

    return provider
  }
}
