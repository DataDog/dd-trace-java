package datadog.trace.agent.test.utils

import datadog.trace.api.Config
import lombok.SneakyThrows

import java.util.concurrent.Callable

class ConfigUtils {
  /**
   * Runs the callable with a new config based on the current config
   */
  synchronized static <T extends Object> T withConfigOverride(final String name, final String value, final Callable r) {
    return withConfigOverride(Collections.singletonMap(name, value), r)
  }

  /**
   * Runs the callable with a new config based on the current config
   */
  synchronized static <T extends Object> T withConfigOverride(Map<String, String> overrides, final Callable r) {
    Properties properties = new Properties()

    // Can't use putAll.  Groovy puts GStringImpl and other such things in maps
    overrides.each { k, v -> properties.put(k.toString(), v.toString()) }

    return runWithConfig(r, new Config(properties, Config.get()))
  }

  /**
   * Runs the callable with a new config generated from the current environment
   */
  synchronized static <T extends Object> T withNewConfig(final Callable<T> r) {
    return runWithConfig(r, new Config())
  }

  @SneakyThrows
  private synchronized static <T extends Object> T runWithConfig(Callable<T> r, Config newConfig) {
    def existingConfig = Config.get()
    Config.set(newConfig)

    try {
      return r.call()
    } finally {
      Config.set(existingConfig)
    }
  }
}
