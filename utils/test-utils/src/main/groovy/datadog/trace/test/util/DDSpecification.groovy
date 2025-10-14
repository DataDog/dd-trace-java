package datadog.trace.test.util

import datadog.environment.EnvironmentVariables
import de.thetaphi.forbiddenapis.SuppressForbidden
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import spock.lang.Shared
import spock.lang.Specification

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier

@SuppressForbidden
@SuppressFBWarnings(value = "PA_PUBLIC_PRIMITIVE_ATTRIBUTE", justification = "Spock inject fields")
abstract class DDSpecification extends Specification {
  private static final CHECK_TIMEOUT_MS = 3000

  static final String CONTEXT_BINDER = "datadog.context.ContextBinder"
  static final String CONTEXT_MANAGER = "datadog.context.ContextManager"
  static final String INST_CONFIG = "datadog.trace.api.InstrumenterConfig"
  static final String CONFIG = "datadog.trace.api.Config"

  private static Field instConfigInstanceField
  private static Constructor instConfigConstructor
  private static Field configInstanceField
  private static Constructor configConstructor

  static {
    allowContextTesting()
    makeConfigInstanceModifiable()
  }

  private static Boolean contextTestingAllowed

  // Keep track of config instance already made modifiable
  private static isConfigInstanceModifiable = false
  static configModificationFailed = false

  @Shared
  protected static ControllableEnvironmentVariables environmentVariables = ControllableEnvironmentVariables.setup()

  // Intentionally saving and restoring System properties.
  private static Properties originalSystemProperties

  protected boolean assertThreadsEachCleanup = true

  @Shared
  private boolean ignoreThreadCleanup

  static void allowContextTesting() {
    if (contextTestingAllowed == null) {
      try {
        contextTestingAllowed =
          Class.forName(CONTEXT_BINDER).allowTesting() &&
          Class.forName(CONTEXT_MANAGER).allowTesting()
      } catch (ClassNotFoundException e) {
        // don't block testing if these types aren't found (project doesn't use context API)
        contextTestingAllowed = e.message == CONTEXT_BINDER || e.message == CONTEXT_MANAGER
      } catch (Throwable ignore) {
        contextTestingAllowed = false
      }
    }
  }

  static void makeConfigInstanceModifiable() {
    if (isConfigInstanceModifiable || configModificationFailed) {
      return
    }

    try {
      Class instConfigClass = Class.forName(INST_CONFIG)
      instConfigInstanceField = instConfigClass.getDeclaredField("INSTANCE")
      instConfigConstructor = instConfigClass.getDeclaredConstructor()
      instConfigConstructor.setAccessible(true)
      Class configClass = Class.forName(CONFIG)
      configInstanceField = configClass.getDeclaredField("INSTANCE")
      configConstructor = configClass.getDeclaredConstructor()
      configConstructor.setAccessible(true)

      isConfigInstanceModifiable = true
    } catch (ClassNotFoundException e) {
      if (e.getMessage() == INST_CONFIG || e.getMessage() == CONFIG) {
        println("Config class not found in this classloader. Not transforming it")
      } else {
        configModificationFailed = true
        println("Config will not be modifiable")
        e.printStackTrace()
      }
    } catch (ReflectiveOperationException e) {
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
  }

  void setupSpec() {
    assert !configModificationFailed: "Config class modification failed.  Ensure all test classes extend DDSpecification"
    assert EnvironmentVariables.getAll().findAll { it.key.startsWith("DD_") }.isEmpty()
    assert systemPropertiesExceptAllowed().findAll { it.key.toString().startsWith("dd.") }.isEmpty()
    assert contextTestingAllowed: "Context not ready for testing.  Ensure all test classes extend DDSpecification"

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

    assert EnvironmentVariables.getAll().findAll { it.key.startsWith("DD_") }.isEmpty()
    assert systemPropertiesExceptAllowed().findAll { it.key.toString().startsWith("dd.") }.isEmpty()

    if (isConfigInstanceModifiable) {
      rebuildConfig()
    }

    checkThreads()
  }

  private static Map<Object, Object> systemPropertiesExceptAllowed() {
    def allowlist = [
      'dd.appsec.enabled',
      'dd.iast.enabled',
      'dd.integration.grizzly-filterchain.enabled',
    ]
    System.getProperties()
      .findAll { key, value -> !allowlist.contains(key as String) }
  }

  void setup() {
    restoreProperties()

    assert EnvironmentVariables.getAll().findAll { it.key.startsWith("DD_") }.isEmpty()
    assert systemPropertiesExceptAllowed().findAll { it.key.toString().startsWith("dd.") }.isEmpty()

    if (isConfigInstanceModifiable) {
      rebuildConfig()
    }
  }

  void cleanup() {
    environmentVariables.clear()

    restoreProperties()

    assert EnvironmentVariables.getAll().findAll { it.key.startsWith("DD_") }.isEmpty()
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
    environmentVariables.removePrefixed(prefixedName)
    rebuildConfig()
  }

  /**
   * Reset the global configuration. Please note that Runtime ID is preserved to the pre-existing value.
   */
  void rebuildConfig() {
    synchronized (DDSpecification) {
      checkConfigTransformation()

      def newInstConfig = instConfigConstructor.newInstance()
      instConfigInstanceField.set(null, newInstConfig)
      def newConfig = configConstructor.newInstance()
      configInstanceField.set(null, newConfig)
    }
  }

  private static void checkConfigTransformation() {
    // Ensure the class was re-transformed properly in DDSpecification.makeConfigInstanceModifiable()
    assert isConfigInstanceModifiable
    assert instConfigConstructor != null
    checkWritable(instConfigInstanceField)
    assert configConstructor != null
    checkWritable(configInstanceField)
  }

  private static void checkWritable(Field field) {
    assert field != null
    assert Modifier.isPublic(field.getModifiers())
    assert Modifier.isStatic(field.getModifiers())
    assert Modifier.isVolatile(field.getModifiers())
    assert !Modifier.isFinal(field.getModifiers())
  }
}

