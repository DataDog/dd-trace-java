package datadog.trace.test.util

import de.thetaphi.forbiddenapis.SuppressForbidden
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.Transformer
import net.bytebuddy.utility.JavaModule
import org.junit.Rule
import spock.lang.Shared
import spock.lang.Specification

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier

import static net.bytebuddy.description.modifier.FieldManifestation.VOLATILE
import static net.bytebuddy.description.modifier.Ownership.STATIC
import static net.bytebuddy.description.modifier.Visibility.PUBLIC
import static net.bytebuddy.matcher.ElementMatchers.named
import static net.bytebuddy.matcher.ElementMatchers.none

@SuppressForbidden
abstract class DDSpecification extends Specification {
  private static final CHECK_TIMEOUT_MS = 3000

  static final String CONFIG = "datadog.trace.api.Config"

  private static Field configInstanceField
  private static Constructor configConstructor

  static {
    makeConfigInstanceModifiable()
  }

  // Keep track of config instance already made modifiable
  private static isConfigInstanceModifiable = false
  static configModificationFailed = false

  @Rule
  public final ResetControllableEnvironmentVariables environmentVariables = new ResetControllableEnvironmentVariables()

  // Intentionally not using the RestoreSystemProperties @Rule because this needs to save properties
  // in the BeforeClass stage instead of Before stage.  Even manually calling before()/after
  // doesn't work because the properties object is not cloned for each invocation
  private static Properties originalSystemProperties

  protected boolean assertThreadsEachCleanup = true

  @Shared
  private boolean ignoreThreadCleanup

  static void makeConfigInstanceModifiable() {
    if (isConfigInstanceModifiable || configModificationFailed) {
      return
    }

    try {
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
        .transform { builder, typeDescription, classLoader, module, pd ->
          builder
            .field(named("INSTANCE"))
            .transform(Transformer.ForField.withModifiers(PUBLIC, STATIC, VOLATILE))
        }
        .with(new ConfigInstrumentationFailedListener())
        .installOn(instrumentation)

      Class configClass = Class.forName(CONFIG)
      configInstanceField = configClass.getDeclaredField("INSTANCE")
      configConstructor = configClass.getDeclaredConstructor()
      configConstructor.setAccessible(true)
      isConfigInstanceModifiable = true
    } catch (ClassNotFoundException e) {
      if (e.getMessage() == CONFIG) {
        println("Config class not found in this classloader. Not transforming it")
      } else {
        configModificationFailed = true
        println("Config will not be modifiable")
        e.printStackTrace()
      }
    } catch (ReflectiveOperationException | IllegalStateException e) {
      configModificationFailed = true
      println("Config will not be modifiable")
      e.printStackTrace()
    }
  }

  private void saveProperties() {
    originalSystemProperties = new Properties()
    originalSystemProperties.putAll(System.properties)
  }

  private void restoreProperties() {
    if (originalSystemProperties != null) {
      Properties copy = new Properties()
      copy.putAll(originalSystemProperties)
      System.setProperties(copy)
    }

    environmentVariables?.reset()
  }

  void setupSpec() {
    assert !configModificationFailed: "Config class modification failed.  Ensure all test classes extend DDSpecification"
    assert System.getenv().findAll { it.key.startsWith("DD_") }.isEmpty()
    assert systemPropertiesExceptAllowed().findAll { it.key.toString().startsWith("dd.") }.isEmpty()

    if (getDDThreads().isEmpty()) {
      ignoreThreadCleanup = false
    } else {
      println "Found DD threads before test started.  Ignoring thread cleanup for this test class"
      ignoreThreadCleanup = true
    }

    saveProperties()
  }

  void cleanupSpec() {
    restoreProperties()

    assert System.getenv().findAll { it.key.startsWith("DD_") }.isEmpty()
    assert systemPropertiesExceptAllowed().findAll { it.key.toString().startsWith("dd.") }.isEmpty()

    if (isConfigInstanceModifiable) {
      rebuildConfig()
    }

    checkThreads()
  }

  private static Map<Object, Object> systemPropertiesExceptAllowed() {
    def allowlist = ['dd.appsec.enabled', 'dd.iast.enabled']
    System.getProperties()
      .findAll { key, value -> !allowlist.contains(key as String) }
  }

  void setup() {
    restoreProperties()

    assert System.getenv().findAll { it.key.startsWith("DD_") }.isEmpty()
    assert systemPropertiesExceptAllowed().findAll { it.key.toString().startsWith("dd.") }.isEmpty()

    if (isConfigInstanceModifiable) {
      rebuildConfig()
    }
  }

  void cleanup() {
    restoreProperties()

    assert System.getenv().findAll { it.key.startsWith("DD_") }.isEmpty()
    assert systemPropertiesExceptAllowed().findAll { it.key.toString().startsWith("dd.") }.isEmpty()

    if (isConfigInstanceModifiable) {
      rebuildConfig()
    }

    if (assertThreadsEachCleanup) {
      checkThreads()
    }
  }

  Set<Thread> getDDThreads() {
    return Thread.getAllStackTraces()
      .keySet()
      .findAll {
        it.name.startsWith("dd-") &&
          it.name != "dd-task-scheduler" &&
          it.name != "dd-cassandra-session-executor" // cassandra instrumentation thread pool
      }
  }

  def checkThreads() {
    if (ignoreThreadCleanup) {
      return
    }

    // Give some time for threads to finish to prevent the race
    // between test cleanup and these assertions
    long deadline = System.currentTimeMillis() + CHECK_TIMEOUT_MS

    Set<Thread> threads = getDDThreads()
    while (System.currentTimeMillis() < deadline && !threads.isEmpty()) {
      Thread.sleep(100)
      threads = getDDThreads()
    }

    if (!threads.isEmpty()) {
      println("WARNING: DD threads still active.  Forget to close() a tracer?")
      println threads.collect { it.name }
    }
  }

  void injectSysConfig(String name, String value, boolean addPrefix = true) {
    checkConfigTransformation()

    String prefixedName = name.startsWith("dd.") || !addPrefix ? name : "dd." + name
    System.setProperty(prefixedName, value)
    rebuildConfig()
  }

  void removeSysConfig(String name, boolean addPrefix = true) {
    checkConfigTransformation()

    String prefixedName = name.startsWith("dd.") || !addPrefix ? name : "dd." + name
    System.clearProperty(prefixedName)
    rebuildConfig()
  }

  void injectEnvConfig(String name, String value, boolean addPrefix = true) {
    checkConfigTransformation()

    String prefixedName = name.startsWith("DD_") || !addPrefix ? name : "DD_" + name
    environmentVariables.set(prefixedName, value)
    rebuildConfig()
  }

  void removeEnvConfig(String name, boolean addPrefix = true) {
    checkConfigTransformation()

    String prefixedName = name.startsWith("DD_") || !addPrefix ? name : "DD_" + name
    environmentVariables.clear(prefixedName)
    rebuildConfig()
  }

  /**
   * Reset the global configuration. Please note that Runtime ID is preserved to the pre-existing value.
   */
  synchronized static void rebuildConfig() {
    checkConfigTransformation()

    def newConfig = configConstructor.newInstance()
    configInstanceField.set(null, newConfig)
  }

  private static void checkConfigTransformation() {
    // Ensure the class was re-transformed properly in DDSpecification.makeConfigInstanceModifiable()

    assert isConfigInstanceModifiable
    assert configInstanceField != null
    assert configConstructor != null
    assert Modifier.isPublic(configInstanceField.getModifiers())
    assert Modifier.isStatic(configInstanceField.getModifiers())
    assert Modifier.isVolatile(configInstanceField.getModifiers())
    assert !Modifier.isFinal(configInstanceField.getModifiers())
  }
}

class ConfigInstrumentationFailedListener extends AgentBuilder.Listener.Adapter {
  @Override
  void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
    if (DDSpecification.CONFIG == typeName) {
      DDSpecification.configModificationFailed = true
    }
  }
}
