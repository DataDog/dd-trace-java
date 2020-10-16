package datadog.trace.agent.test.utils

import datadog.trace.api.Config

import java.lang.reflect.Modifier
import java.util.concurrent.Callable

class ConfigUtils {

  static final CONFIG_INSTANCE_FIELD = Config.getDeclaredField("INSTANCE")

  /**
   * Provides an callback to set up the testing environment and reset the global configuration after system properties and envs are set.
   *
   * @param r
   * @return
   */
  static updateConfig(final Callable r) {
    r.call()
    rebuildConfig()
  }

  /**
   * Reset the global configuration. Please note that Runtime ID is preserved to the pre-existing value.
   */
  static void rebuildConfig() {
    // Ensure the class was re-transformed properly in DDSpecification.makeConfigInstanceModifiable()
    assert Modifier.isPublic(CONFIG_INSTANCE_FIELD.getModifiers())
    assert Modifier.isStatic(CONFIG_INSTANCE_FIELD.getModifiers())
    assert Modifier.isVolatile(CONFIG_INSTANCE_FIELD.getModifiers())
    assert !Modifier.isFinal(CONFIG_INSTANCE_FIELD.getModifiers())

    def newConfig = new Config()
    CONFIG_INSTANCE_FIELD.set(null, newConfig)
  }
}


