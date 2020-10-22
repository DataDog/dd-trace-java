package datadog.trace.test.util

import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.Transformer
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables
import spock.lang.Specification

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier

import static net.bytebuddy.description.modifier.FieldManifestation.VOLATILE
import static net.bytebuddy.description.modifier.Ownership.STATIC
import static net.bytebuddy.description.modifier.Visibility.PUBLIC
import static net.bytebuddy.matcher.ElementMatchers.named
import static net.bytebuddy.matcher.ElementMatchers.none

abstract class DDSpecification extends Specification {
  private static final String CONFIG = "datadog.trace.api.Config"

  private static Field CONFIG_INSTANCE_FIELD
  private static Constructor CONFIG_CONSTRUCTOR

  static {
    makeConfigInstanceModifiable()
  }

  // Keep track of config instance already made modifiable
  private static isConfigInstanceModifiable = false

  @Rule
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables()

  // Intentionally not using the RestoreSystemProperties @Rule because this needs to save properties
  // in the BeforeClass stage instead of Before stage.  Even manually calling before()/after
  // doesn't work because the properties object is not cloned for each invocation
  private static Properties ORIGINAL_SYSTEM_PROPERTIES

  static void makeConfigInstanceModifiable() {
    if (isConfigInstanceModifiable) {
      return
    }

    def instrumentation = ByteBuddyAgent.install()
    new AgentBuilder.Default()
      .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
      .with(AgentBuilder.RedefinitionStrategy.Listener.ErrorEscalating.FAIL_FAST)
    // Config is injected into the bootstrap, so we need to provide a locator.
      .with(
        new AgentBuilder.LocationStrategy.Simple(
          ClassFileLocator.ForClassLoader.ofSystemLoader()))
      .ignore(none()) // Allow transforming bootstrap classes
      .type(named(CONFIG))
      .transform { builder, typeDescription, classLoader, module ->
        builder
          .field(named("INSTANCE"))
          .transform(Transformer.ForField.withModifiers(PUBLIC, STATIC, VOLATILE))
      }
      .installOn(instrumentation)

    Class configClass = Class.forName(CONFIG)
    CONFIG_INSTANCE_FIELD = configClass.getDeclaredField("INSTANCE")
    CONFIG_CONSTRUCTOR = configClass.getDeclaredConstructor()
    CONFIG_CONSTRUCTOR.setAccessible(true)

    isConfigInstanceModifiable = true
  }

  private void saveProperties() {
    ORIGINAL_SYSTEM_PROPERTIES = new Properties()
    ORIGINAL_SYSTEM_PROPERTIES.putAll(System.properties)
  }

  private void restoreProperties() {
    Properties copy = new Properties()
    copy.putAll(ORIGINAL_SYSTEM_PROPERTIES)
    System.setProperties(copy)
  }

  void setupSpec() {
    assert System.getenv().findAll { it.key.startsWith("DD_") }.isEmpty()
    assert System.getProperties().findAll { it.key.toString().startsWith("dd.") }.isEmpty()

    saveProperties()
  }

  void cleanupSpec() {
    restoreProperties()

    assert System.getenv().findAll { it.key.startsWith("DD_") }.isEmpty()
    assert System.getProperties().findAll { it.key.toString().startsWith("dd.") }.isEmpty()

    rebuildConfig()
  }

  void setup() {
    restoreProperties()

    assert System.getenv().findAll { it.key.startsWith("DD_") }.isEmpty()
    assert System.getProperties().findAll { it.key.toString().startsWith("dd.") }.isEmpty()

    rebuildConfig()
  }

  void injectSysConfig(String name, String value) {
    String prefixedName = name.startsWith("dd.") ? name : "dd." + name
    System.setProperty(prefixedName, value)
    rebuildConfig()
  }

  void removeSysConfig(String name) {
    String prefixedName = name.startsWith("dd.") ? name : "dd." + name
    System.clearProperty(prefixedName)
    rebuildConfig()
  }

  void injectEnvConfig(String name, String value) {
    String prefixedName = name.startsWith("DD_") ? name : "DD_" + name
    environmentVariables.set(prefixedName, value)
    rebuildConfig()
  }

  void removeEnvConfig(String name) {
    String prefixedName = name.startsWith("DD_") ? name : "DD_" + name
    environmentVariables.clear(prefixedName)
    rebuildConfig()
  }

  /**
   * Reset the global configuration. Please note that Runtime ID is preserved to the pre-existing value.
   */
  synchronized static void rebuildConfig() {
    // Ensure the class was re-transformed properly in DDSpecification.makeConfigInstanceModifiable()
    assert Modifier.isPublic(CONFIG_INSTANCE_FIELD.getModifiers())
    assert Modifier.isStatic(CONFIG_INSTANCE_FIELD.getModifiers())
    assert Modifier.isVolatile(CONFIG_INSTANCE_FIELD.getModifiers())
    assert !Modifier.isFinal(CONFIG_INSTANCE_FIELD.getModifiers())

    def newConfig = CONFIG_CONSTRUCTOR.newInstance()
    CONFIG_INSTANCE_FIELD.set(null, newConfig)
  }
}
