package datadog.trace.junit.utils.config;

import static net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy.Listener.ErrorEscalating.FAIL_FAST;
import static net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy.RETRANSFORMATION;
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
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.Transformer;
import net.bytebuddy.utility.JavaModule;
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
 *   <li>Making {@code Config} and {@code InstrumenterConfig} singletons modifiable via ByteBuddy
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

  private static Field instConfigInstanceField;
  private static Constructor<?> instConfigConstructor;
  private static Field configInstanceField;
  private static Constructor<?> configConstructor;

  private static volatile boolean isConfigInstanceModifiable = false;
  private static volatile boolean configModificationFailed = false;

  static final TestEnvironmentVariables environmentVariables = TestEnvironmentVariables.setup();

  private static Properties originalSystemProperties;

  // region JUnit lifecycle callbacks

  @Override
  public void beforeAll(ExtensionContext context) {
    installConfigTransformer();
    makeConfigInstanceModifiable();
    assertFalse(configModificationFailed, "Config class modification failed");
    if (originalSystemProperties == null) {
      saveProperties();
    }
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    restoreProperties();
    environmentVariables.clear();
    if (isConfigInstanceModifiable) {
      rebuildConfig();
    }
    applyDeclaredConfig(context);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    environmentVariables.clear();
    restoreProperties();
    if (isConfigInstanceModifiable) {
      rebuildConfig();
    }
  }

  @Override
  public void afterAll(ExtensionContext context) {
    restoreProperties();
    if (isConfigInstanceModifiable) {
      rebuildConfig();
    }
  }

  private void applyDeclaredConfig(ExtensionContext context) {
    // Class-level @WithConfig annotations
    // Walk the entire class hierarchy so annotations on superclasses are applied
    // (topmost first, then subclass overrides)
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
      injectEnvConfig(cfg.key(), cfg.value(), cfg.addPrefix());
    } else {
      injectSysConfig(cfg.key(), cfg.value(), cfg.addPrefix());
    }
  }

  // endregion

  // region Public static API for imperative config injection

  public static void injectSysConfig(String name, String value) {
    injectSysConfig(name, value, true);
  }

  public static void injectSysConfig(String name, String value, boolean addPrefix) {
    checkConfigTransformation();
    String prefixedName = name.startsWith("dd.") || !addPrefix ? name : "dd." + name;
    System.setProperty(prefixedName, value);
    rebuildConfig();
  }

  public static void removeSysConfig(String name) {
    removeSysConfig(name, true);
  }

  public static void removeSysConfig(String name, boolean addPrefix) {
    checkConfigTransformation();
    String prefixedName = name.startsWith("dd.") || !addPrefix ? name : "dd." + name;
    System.clearProperty(prefixedName);
    rebuildConfig();
  }

  public static void injectEnvConfig(String name, String value) {
    injectEnvConfig(name, value, true);
  }

  public static void injectEnvConfig(String name, String value, boolean addPrefix) {
    checkConfigTransformation();
    String prefixedName = name.startsWith("DD_") || !addPrefix ? name : "DD_" + name;
    environmentVariables.set(prefixedName, value);
    rebuildConfig();
  }

  public static void removeEnvConfig(String name) {
    removeEnvConfig(name, true);
  }

  public static void removeEnvConfig(String name, boolean addPrefix) {
    checkConfigTransformation();
    String prefixedName = name.startsWith("DD_") || !addPrefix ? name : "DD_" + name;
    environmentVariables.removePrefixed(prefixedName);
    rebuildConfig();
  }

  // endregion

  // region Config infrastructure setup

  private static void installConfigTransformer() {
    try {
      Instrumentation instrumentation = ByteBuddyAgent.install();
      new AgentBuilder.Default()
          .with(RETRANSFORMATION)
          .with(FAIL_FAST)
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
        System.err.println("Config class not found in this classloader. Not transforming it");
      } else {
        configModificationFailed = true;
        System.err.println("Config will not be modifiable");
        e.printStackTrace();
      }
    } catch (ReflectiveOperationException e) {
      configModificationFailed = true;
      System.err.println("Config will not be modifiable");
      e.printStackTrace();
    }
  }

  private static void rebuildConfig() {
    synchronized (WithConfigExtension.class) {
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

  // region Validation

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
    public String get(@NonNull String name) {
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
        @NonNull String typeName,
        ClassLoader classLoader,
        JavaModule module,
        boolean loaded,
        @NonNull Throwable throwable) {
      if (CONFIG.equals(typeName)) {
        configModificationFailed = true;
      }
    }
  }
}
