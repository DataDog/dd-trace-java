package datadog.trace.agent.test.utils

import datadog.trace.api.Config
import lombok.SneakyThrows

import java.lang.reflect.Modifier
import java.util.concurrent.Callable

class ConfigUtils {

  static final CONFIG_INSTANCE_FIELD = Config.getDeclaredField("INSTANCE")
  static final RUNTIME_ID_FIELD = Config.getDeclaredField("runtimeId")

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
    // Ensure the class was retransformed properly in DDSpecification.makeConfigInstanceModifiable()
    assert Modifier.isPublic(CONFIG_INSTANCE_FIELD.getModifiers())
    assert Modifier.isStatic(CONFIG_INSTANCE_FIELD.getModifiers())
    assert Modifier.isVolatile(CONFIG_INSTANCE_FIELD.getModifiers())
    assert !Modifier.isFinal(CONFIG_INSTANCE_FIELD.getModifiers())

    assert Modifier.isPublic(RUNTIME_ID_FIELD.getModifiers())
    assert !Modifier.isStatic(RUNTIME_ID_FIELD.getModifiers())
    assert Modifier.isVolatile(RUNTIME_ID_FIELD.getModifiers())
    assert !Modifier.isFinal(RUNTIME_ID_FIELD.getModifiers())

    def previousConfig = CONFIG_INSTANCE_FIELD.get(null)
    CONFIG_INSTANCE_FIELD.set(null, newConfig)

    if (previousConfig != null) {
      RUNTIME_ID_FIELD.set(newConfig, RUNTIME_ID_FIELD.get(previousConfig))
    }

    try {
      return r.call()
    } finally {
      CONFIG_INSTANCE_FIELD.set(null, previousConfig)
    }
  }
}
