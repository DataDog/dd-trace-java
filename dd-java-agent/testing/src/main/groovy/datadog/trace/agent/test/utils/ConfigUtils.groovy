package datadog.trace.agent.test.utils

import datadog.trace.api.Config
import lombok.SneakyThrows

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Callable

class ConfigUtils {
  private static class ConfigInstance {
    // Wrapped in a static class to lazy load.
    static final FIELD = Config.getDeclaredField("INSTANCE")
    static final RUNTIME_ID_FIELD = Config.getDeclaredField("runtimeId")
  }

  @SneakyThrows
  synchronized static <T extends Object> Object withConfigOverride(final String name, final String value, final Callable<T> r) {
    makeConfigInstanceModifiable()

    def existingConfig = Config.get()
    Properties properties = new Properties()
    properties.put(name, value)
    ConfigInstance.FIELD.set(null, new Config(properties, existingConfig))
    assert Config.get() != existingConfig
    try {
      return r.call()
    } finally {
      ConfigInstance.FIELD.set(null, existingConfig)
    }
  }

  /**
   * Provides an callback to set up the testing environment and reset the global configuration after system properties and envs are set.
   *
   * @param r
   * @return
   */
  static updateConfig(final Callable r) {
    makeConfigInstanceModifiable()
    r.call()

    def previousConfig = ConfigInstance.FIELD.get(null)
    def newConfig = new Config()
    ConfigInstance.FIELD.set(null, newConfig)
    if (previousConfig != null) {
      ConfigInstance.RUNTIME_ID_FIELD.set(newConfig, ConfigInstance.RUNTIME_ID_FIELD.get(previousConfig))
    }
  }

  // Keep track of config instance already made modifiable
  private static isConfigInstanceModifiable = false

  private static Field getModifiersField() {
    // Sometime between Java8 and 12, Field.class was added to the fieldFilterMap of Reflection.class
    // so Field.getDeclaredField("modifiers") doesn't work.  This is a workaround

    Method method = Class.getDeclaredMethod("getDeclaredFields0", Boolean.TYPE)
    method.setAccessible(true)
    Field[] fields = (Field[]) method.invoke(Field, false)

    for (Field field : fields) {
      if ("modifiers".equals(field.getName())) {
        return field
      }
    }

    // This can't happen
    throw new RuntimeException("Unable to get modifiers field")
  }

  private static void changeModifiers(Field field, int modifierMask) {
    Field modifiersField = getModifiersField()
    modifiersField.setAccessible(true)
    modifiersField.setInt(field, modifierMask)
    field.setAccessible(true)
  }

  private static void makeConfigInstanceModifiable() {
    if (isConfigInstanceModifiable) {
      assertFieldState()
      return
    }

    changeModifiers(ConfigInstance.FIELD, Modifier.PUBLIC | Modifier.STATIC | Modifier.VOLATILE)
    changeModifiers(ConfigInstance.RUNTIME_ID_FIELD, Modifier.PUBLIC | Modifier.VOLATILE)

    assertFieldState()
    isConfigInstanceModifiable = true
  }

  private static void assertFieldState() {
    assert Modifier.isPublic(ConfigInstance.FIELD.getModifiers())
    assert Modifier.isStatic(ConfigInstance.FIELD.getModifiers())
    assert Modifier.isVolatile(ConfigInstance.FIELD.getModifiers())
    assert !Modifier.isFinal(ConfigInstance.FIELD.getModifiers())

    assert Modifier.isPublic(ConfigInstance.RUNTIME_ID_FIELD.getModifiers())
    assert !Modifier.isStatic(ConfigInstance.RUNTIME_ID_FIELD.getModifiers())
    assert Modifier.isVolatile(ConfigInstance.RUNTIME_ID_FIELD.getModifiers())
    assert !Modifier.isFinal(ConfigInstance.RUNTIME_ID_FIELD.getModifiers())
  }
}
