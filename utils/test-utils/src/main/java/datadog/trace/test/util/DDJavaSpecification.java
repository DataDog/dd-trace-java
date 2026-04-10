package datadog.trace.test.util;

import static net.bytebuddy.description.modifier.FieldManifestation.VOLATILE;
import static net.bytebuddy.description.modifier.Ownership.STATIC;
import static net.bytebuddy.description.modifier.Visibility.PUBLIC;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.environment.EnvironmentVariables;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.Transformer;
import net.bytebuddy.utility.JavaModule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

@SuppressForbidden
public class DDJavaSpecification {

  private static final long CHECK_TIMEOUT_MS = 3000;

  static final String CONTEXT_BINDER = "datadog.context.ContextBinder";
  static final String CONTEXT_MANAGER = "datadog.context.ContextManager";
  static final String INST_CONFIG = "datadog.trace.api.InstrumenterConfig";
  static final String CONFIG = "datadog.trace.api.Config";

  private static Field instConfigInstanceField;
  private static Constructor<?> instConfigConstructor;
  private static Field configInstanceField;
  private static Constructor<?> configConstructor;

  private static Boolean contextTestingAllowed;
  private static volatile boolean isConfigInstanceModifiable = false;
  static volatile boolean configModificationFailed = false;

  protected static final TestEnvironmentVariables environmentVariables =
      TestEnvironmentVariables.setup();

  private static Properties originalSystemProperties;

  protected boolean assertThreadsEachCleanup = true;
  private static volatile boolean ignoreThreadCleanup;

  @BeforeAll
  static void beforeAll() {
    allowContextTesting();
    installConfigTransformer();
    makeConfigInstanceModifiable();
    assertFalse(
        configModificationFailed,
        "Config class modification failed. Ensure all test classes extend DDJavaSpecification");
    assertTrue(
        EnvironmentVariables.getAll().entrySet().stream()
            .noneMatch(e -> e.getKey().startsWith("DD_")));
    assertTrue(
        systemPropertiesExceptAllowed().entrySet().stream()
            .noneMatch(e -> e.getKey().toString().startsWith("dd.")));
    assertTrue(
        contextTestingAllowed,
        "Context not ready for testing. Ensure all test classes extend DDJavaSpecification");

    if (getDDThreads().isEmpty()) {
      ignoreThreadCleanup = false;
    } else {
      System.out.println(
          "Found DD threads before test started. Ignoring thread cleanup for this test class");
      ignoreThreadCleanup = true;
    }
    saveProperties();
  }

  static void allowContextTesting() {
    if (contextTestingAllowed == null) {
      try {
        Class<?> binderClass = Class.forName(CONTEXT_BINDER);
        Method binderAllowTesting = binderClass.getDeclaredMethod("allowTesting");
        binderAllowTesting.setAccessible(true);
        Class<?> managerClass = Class.forName(CONTEXT_MANAGER);
        Method managerAllowTesting = managerClass.getDeclaredMethod("allowTesting");
        managerAllowTesting.setAccessible(true);
        contextTestingAllowed =
            (Boolean) binderAllowTesting.invoke(null) && (Boolean) managerAllowTesting.invoke(null);
      } catch (ClassNotFoundException e) {
        // don't block testing if these types aren't found (project doesn't use context API)
        contextTestingAllowed =
            CONTEXT_BINDER.equals(e.getMessage()) || CONTEXT_MANAGER.equals(e.getMessage());
      } catch (Throwable ignore) {
        contextTestingAllowed = false;
      }
    }
  }

  private static void installConfigTransformer() {
    try {
      Instrumentation instrumentation = ByteBuddyAgent.install();
      new AgentBuilder.Default()
          .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
          .with(AgentBuilder.RedefinitionStrategy.Listener.ErrorEscalating.FAIL_FAST)
          .with(
              new AgentBuilder.LocationStrategy.Simple(
                  ClassFileLocator.ForClassLoader.ofSystemLoader()))
          .ignore(none())
          .type(namedOneOf(INST_CONFIG, CONFIG))
          .transform(
              (builder, typeDescription, classLoader, module, pd) ->
                  builder
                      .field(named("INSTANCE"))
                      .transform(Transformer.ForField.withModifiers(PUBLIC, STATIC, VOLATILE)))
          .with(new ConfigInstrumentationFailedListener())
          .installOn(instrumentation);
    } catch (IllegalStateException e) {
      // Ignore. When we have -javaagent:dd-java-agent.jar, this is fine.
    }
  }

  static void makeConfigInstanceModifiable() {
    if (isConfigInstanceModifiable || configModificationFailed) {
      return;
    }

    try {
      Class<?> instConfigClass = Class.forName(INST_CONFIG);
      instConfigInstanceField = instConfigClass.getDeclaredField("INSTANCE");
      instConfigConstructor = instConfigClass.getDeclaredConstructor();
      instConfigConstructor.setAccessible(true);
      Class<?> configClass = Class.forName(CONFIG);
      configInstanceField = configClass.getDeclaredField("INSTANCE");
      configConstructor = configClass.getDeclaredConstructor();
      configConstructor.setAccessible(true);

      isConfigInstanceModifiable = true;
    } catch (ClassNotFoundException e) {
      if (INST_CONFIG.equals(e.getMessage()) || CONFIG.equals(e.getMessage())) {
        System.out.println("Config class not found in this classloader. Not transforming it");
      } else {
        configModificationFailed = true;
        System.out.println("Config will not be modifiable");
        e.printStackTrace();
      }
    } catch (ReflectiveOperationException e) {
      configModificationFailed = true;
      System.out.println("Config will not be modifiable");
      e.printStackTrace();
    }
  }

  private static void saveProperties() {
    originalSystemProperties = new Properties();
    originalSystemProperties.putAll(System.getProperties());
  }

  private static void restoreProperties() {
    if (originalSystemProperties != null) {
      Properties copy = new Properties();
      copy.putAll(originalSystemProperties);
      System.setProperties(copy);
    }
  }

  @AfterAll
  static void afterAll() {
    restoreProperties();

    assertTrue(
        EnvironmentVariables.getAll().entrySet().stream()
            .noneMatch(e -> e.getKey().startsWith("DD_")));
    assertTrue(
        systemPropertiesExceptAllowed().entrySet().stream()
            .noneMatch(e -> e.getKey().toString().startsWith("dd.")));

    if (isConfigInstanceModifiable) {
      rebuildConfig();
    }

    checkThreads();
  }

  private static Map<Object, Object> systemPropertiesExceptAllowed() {
    List<String> allowlist =
        Arrays.asList(
            "dd.appsec.enabled", "dd.iast.enabled", "dd.integration.grizzly-filterchain.enabled");
    return System.getProperties().entrySet().stream()
        .filter(e -> !allowlist.contains(String.valueOf(e.getKey())))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  @BeforeEach
  void setup() {
    restoreProperties();

    assertTrue(
        EnvironmentVariables.getAll().entrySet().stream()
            .noneMatch(e -> e.getKey().startsWith("DD_")));
    assertTrue(
        systemPropertiesExceptAllowed().entrySet().stream()
            .noneMatch(e -> e.getKey().toString().startsWith("dd.")));

    if (isConfigInstanceModifiable) {
      rebuildConfig();
    }
  }

  @AfterEach
  void cleanup() {
    environmentVariables.clear();

    restoreProperties();

    assertTrue(
        EnvironmentVariables.getAll().entrySet().stream()
            .noneMatch(e -> e.getKey().startsWith("DD_")));
    assertTrue(
        systemPropertiesExceptAllowed().entrySet().stream()
            .noneMatch(e -> e.getKey().toString().startsWith("dd.")));

    if (isConfigInstanceModifiable) {
      rebuildConfig();
    }

    if (assertThreadsEachCleanup) {
      checkThreads();
    }
  }

  static Set<Thread> getDDThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(
            t ->
                t.getName().startsWith("dd-")
                    && !t.getName().equals("dd-task-scheduler")
                    && !t.getName().equals("dd-cassandra-session-executor"))
        .collect(Collectors.toSet());
  }

  static void checkThreads() {
    if (ignoreThreadCleanup) {
      return;
    }

    long deadline = System.currentTimeMillis() + CHECK_TIMEOUT_MS;

    Set<Thread> threads = getDDThreads();
    while (System.currentTimeMillis() < deadline && !threads.isEmpty()) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      threads = getDDThreads();
    }

    if (!threads.isEmpty()) {
      System.out.println("WARNING: DD threads still active. Forget to close() a tracer?");
      List<String> names = threads.stream().map(Thread::getName).collect(Collectors.toList());
      System.out.println(names);
    }
  }

  public void injectSysConfig(String name, String value) {
    injectSysConfig(name, value, true);
  }

  public void injectSysConfig(String name, String value, boolean addPrefix) {
    checkConfigTransformation();

    String prefixedName = name.startsWith("dd.") || !addPrefix ? name : "dd." + name;
    System.setProperty(prefixedName, value);
    rebuildConfig();
  }

  public void removeSysConfig(String name) {
    removeSysConfig(name, true);
  }

  public void removeSysConfig(String name, boolean addPrefix) {
    checkConfigTransformation();

    String prefixedName = name.startsWith("dd.") || !addPrefix ? name : "dd." + name;
    System.clearProperty(prefixedName);
    rebuildConfig();
  }

  public void injectEnvConfig(String name, String value) {
    injectEnvConfig(name, value, true);
  }

  public void injectEnvConfig(String name, String value, boolean addPrefix) {
    checkConfigTransformation();

    String prefixedName = name.startsWith("DD_") || !addPrefix ? name : "DD_" + name;
    environmentVariables.set(prefixedName, value);
    rebuildConfig();
  }

  public void removeEnvConfig(String name) {
    removeEnvConfig(name, true);
  }

  public void removeEnvConfig(String name, boolean addPrefix) {
    checkConfigTransformation();

    String prefixedName = name.startsWith("DD_") || !addPrefix ? name : "DD_" + name;
    environmentVariables.removePrefixed(prefixedName);
    rebuildConfig();
  }

  static void rebuildConfig() {
    synchronized (DDJavaSpecification.class) {
      checkConfigTransformation();
      try {
        Object newInstConfig = instConfigConstructor.newInstance();
        instConfigInstanceField.set(null, newInstConfig);
        Object newConfig = configConstructor.newInstance();
        configInstanceField.set(null, newConfig);
      } catch (ReflectiveOperationException e) {
        throw new AssertionError("Failed to rebuild config", e);
      }
    }
  }

  private static void checkConfigTransformation() {
    assertTrue(isConfigInstanceModifiable);
    assertNotNull(instConfigConstructor);
    checkWritable(instConfigInstanceField);
    assertNotNull(configConstructor);
    checkWritable(configInstanceField);
  }

  private static void checkWritable(Field field) {
    assertNotNull(field);
    assertTrue(Modifier.isPublic(field.getModifiers()));
    assertTrue(Modifier.isStatic(field.getModifiers()));
    assertTrue(Modifier.isVolatile(field.getModifiers()));
    assertFalse(Modifier.isFinal(field.getModifiers()));
  }

  public static class TestEnvironmentVariables
      extends EnvironmentVariables.EnvironmentVariablesProvider {
    private final Map<String, String> env = new HashMap<>();

    TestEnvironmentVariables(String... kv) {
      for (int i = 0; i + 1 < kv.length; i += 2) {
        env.put(kv[i], kv[i + 1]);
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

    @SuppressForbidden
    static TestEnvironmentVariables setup(String... kv) {
      TestEnvironmentVariables provider = new TestEnvironmentVariables(kv);
      EnvironmentVariables.provider = provider;

      String propagateVars = System.getenv("TEST_ENV_PROPAGATE_VARS");
      if (propagateVars != null) {
        for (String envVar : propagateVars.split(",")) {
          provider.env.put(envVar, System.getenv(envVar));
        }
      }

      return provider;
    }
  }

  private static class ConfigInstrumentationFailedListener extends AgentBuilder.Listener.Adapter {
    @Override
    public void onError(
        String typeName,
        ClassLoader classLoader,
        JavaModule module,
        boolean loaded,
        Throwable throwable) {
      if (CONFIG.equals(typeName)) {
        configModificationFailed = true;
      }
    }
  }
}
