package datadog.trace.junit.utils.config;

import datadog.environment.EnvironmentVariables;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * JUnit 5 extension that manages DD config injection for tests. Handles:
 *
 * <ul>
 *   <li>Verifying {@code Config} and {@code InstrumenterConfig} {@code INSTANCE} fields have been
 *       made modifiable by the load-time agent (see {@code
 *       dd-trace-java.modifiable-config.gradle.kts} and {@code buildSrc/modifiable-config-agent})
 *   <li>Saving/restoring system properties between tests
 *   <li>Managing test environment variables
 *   <li>Applying {@link WithConfig} annotations (class and method level, including composed
 *       annotations)
 *   <li>Rebuilding config from a clean slate before each test
 * </ul>
 *
 * <p>This extension is auto-registered when using {@link WithConfig} annotations. It can also be
 * used explicitly via {@code @ExtendWith(WithConfigExtension.class)}.
 */
@SuppressForbidden
public class WithConfigExtension
    implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, AfterAllCallback {

  static final String INST_CONFIG = "datadog.trace.api.InstrumenterConfig";
  static final String CONFIG = "datadog.trace.api.Config";

  private static final Field instConfigInstanceField;
  private static final Constructor<?> instConfigConstructor;
  private static final Field configInstanceField;
  private static final Constructor<?> configConstructor;

  static {
    ensureConfigInstrumentationHasBeenApplied();
    try {
      Class<?> instCfg = Class.forName(INST_CONFIG);
      instConfigInstanceField = instCfg.getDeclaredField("INSTANCE");
      instConfigConstructor = instCfg.getDeclaredConstructor();
      instConfigConstructor.setAccessible(true);
      Class<?> cfg = Class.forName(CONFIG);
      configInstanceField = cfg.getDeclaredField("INSTANCE");
      configConstructor = cfg.getDeclaredConstructor();
      configConstructor.setAccessible(true);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static final TestEnvironmentVariables environmentVariables = TestEnvironmentVariables.setup();

  private static Properties originalSystemProperties;

  // region JUnit lifecycle callbacks

  @Override
  public void beforeAll(ExtensionContext context) {
    // Back up config and apply class-level config values.
    if (originalSystemProperties == null) {
      saveProperties();
    }
    // Apply class-level @WithConfig so config is available before @BeforeAll methods
    applyClassLevelConfig(context);
    rebuildConfig();
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    restoreProperties();
    environmentVariables.clear();
    applyDeclaredConfig(context);
    rebuildConfig();
  }

  @Override
  public void afterEach(ExtensionContext context) {
    environmentVariables.clear();
    restoreProperties();
    rebuildConfig();
  }

  @Override
  public void afterAll(ExtensionContext context) {
    restoreProperties();
    rebuildConfig();
  }

  private static void applyDeclaredConfig(ExtensionContext context) {
    applyClassLevelConfig(context);
    applyMethodLevelConfig(context);
  }

  private static void applyClassLevelConfig(ExtensionContext context) {
    // Walk the entire class hierarchy so annotations on superclasses and apply topmost first, then
    // subclass overrides.
    Class<?> testClass = context.getRequiredTestClass();
    List<Class<?>> hierarchy = new ArrayList<>();
    for (Class<?> cls = testClass; cls != null; cls = cls.getSuperclass()) {
      hierarchy.add(cls);
    }
    for (int i = hierarchy.size() - 1; i >= 0; i--) {
      List<WithConfig> classConfigs =
          AnnotationSupport.findRepeatableAnnotations(hierarchy.get(i), WithConfig.class);
      for (WithConfig cfg : classConfigs) {
        applyConfig(cfg);
      }
    }
  }

  private static void applyMethodLevelConfig(ExtensionContext context) {
    // Method-level @WithConfig annotations (supports composed/meta-annotations)
    context
        .getTestMethod()
        .ifPresent(
            method -> {
              List<WithConfig> methodConfigs =
                  AnnotationSupport.findRepeatableAnnotations(method, WithConfig.class);
              for (WithConfig cfg : methodConfigs) {
                applyConfig(cfg);
              }
            });
  }

  private static void applyConfig(WithConfig cfg) {
    if (cfg.env()) {
      setEnvVariable(cfg.key(), cfg.value(), cfg.addPrefix());
    } else {
      setSysProperty(cfg.key(), cfg.value(), cfg.addPrefix());
    }
  }

  private static void setSysProperty(String name, String value, boolean addPrefix) {
    String prefixedName = addPrefix && !name.startsWith("dd.") ? "dd." + name : name;
    System.setProperty(prefixedName, value);
  }

  private static void setEnvVariable(String name, String value, boolean addPrefix) {
    String prefixedName = addPrefix && !name.startsWith("DD_") ? "DD_" + name : name;
    environmentVariables.set(prefixedName, value);
  }

  // endregion

  // region Public static API for imperative config injection

  public static void injectSysConfig(String name, String value) {
    injectSysConfig(name, value, true);
  }

  public static void injectSysConfig(String name, String value, boolean addPrefix) {
    setSysProperty(name, value, addPrefix);
    rebuildConfig();
  }

  public static void removeSysConfig(String name) {
    removeSysConfig(name, true);
  }

  public static void removeSysConfig(String name, boolean addPrefix) {
    String prefixedName = addPrefix && !name.startsWith("dd.") ? "dd." + name : name;
    System.clearProperty(prefixedName);
    rebuildConfig();
  }

  public static void injectEnvConfig(String name, String value) {
    injectEnvConfig(name, value, true);
  }

  public static void injectEnvConfig(String name, String value, boolean addPrefix) {
    setEnvVariable(name, value, addPrefix);
    rebuildConfig();
  }

  public static void removeEnvConfig(String name) {
    removeEnvConfig(name, true);
  }

  public static void removeEnvConfig(String name, boolean addPrefix) {
    String prefixedName = addPrefix && !name.startsWith("DD_") ? "DD_" + name : name;
    environmentVariables.removePrefixed(prefixedName);
    rebuildConfig();
  }

  // endregion

  // region Config infrastructure setup

  private static void ensureConfigInstrumentationHasBeenApplied() {
    if (isWritableInstance(CONFIG) && isWritableInstance(INST_CONFIG)) {
      return;
    }
    throw new IllegalStateException(
        "Config/InstrumenterConfig INSTANCE fields are not modifiable. "
            + "Need the '-javaagent:modifiable-config-agent.jar' on the test JVM "
            + "(the dd-trace-java.configure-tests Gradle convention plugin wires this automatically).");
  }

  private static boolean isWritableInstance(String className) {
    try {
      int m = Class.forName(className).getDeclaredField("INSTANCE").getModifiers();
      return Modifier.isPublic(m)
          && Modifier.isStatic(m)
          && Modifier.isVolatile(m)
          && !Modifier.isFinal(m);
    } catch (ClassNotFoundException | NoSuchFieldException e) {
      return false;
    }
  }

  private static void rebuildConfig() {
    synchronized (WithConfigExtension.class) {
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

  // endregion

  // region Property management

  static void saveProperties() {
    originalSystemProperties = new Properties();
    originalSystemProperties.putAll(System.getProperties());
  }

  static void restoreProperties() {
    if (originalSystemProperties != null) {
      Properties copy = new Properties();
      copy.putAll(originalSystemProperties);
      System.setProperties(copy);
    }
  }

  // endregion

  /** Test-only environment variable provider that replaces the real one during tests. */
  public static class TestEnvironmentVariables
      extends EnvironmentVariables.EnvironmentVariablesProvider {
    private final Map<String, String> env = new HashMap<>();

    TestEnvironmentVariables(String... kv) {
      for (int i = 0; i + 1 < kv.length; i += 2) {
        this.env.put(kv[i], kv[i + 1]);
      }
    }

    @Override
    public String get(@Nonnull String name) {
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
}
