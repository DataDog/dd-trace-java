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

import datadog.environment.EnvironmentVariables;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 *   <li>Making {@code Config} and {@code InstrumenterConfig} singletons modifiable. Primary
 *       strategy: ByteBuddy retransformation to relax the {@code INSTANCE} fields to {@code public
 *       static volatile}. Fallback strategy (used on JVMs where retransformation silently fails,
 *       e.g. IBM J9 / OpenJ9 / Semeru): direct field writes via {@code
 *       sun.misc.Unsafe.putObjectVolatile} accessed reflectively.
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

  private enum WriteMode {
    /** Field has been retransformed to public/volatile — write via {@link Field#set}. */
    REFLECTION,
    /** Retransformation failed — write via {@code sun.misc.Unsafe.putObjectVolatile}. */
    UNSAFE
  }

  private static WriteMode writeMode;

  private static Field instConfigInstanceField;
  private static Constructor<?> instConfigConstructor;
  private static Field configInstanceField;
  private static Constructor<?> configConstructor;

  // Used in UNSAFE mode only.
  private static UnsafeFieldWriter instConfigUnsafeWriter;
  private static UnsafeFieldWriter configUnsafeWriter;

  private static volatile boolean configTransformerInstalled = false;
  private static volatile boolean isConfigInstanceModifiable = false;
  private static volatile boolean configModificationFailed = false;

  static final TestEnvironmentVariables environmentVariables = TestEnvironmentVariables.setup();

  private static Properties originalSystemProperties;

  // region JUnit lifecycle callbacks

  @Override
  public void beforeAll(ExtensionContext context) {
    if (!configTransformerInstalled) {
      installConfigTransformer();
      configTransformerInstalled = true;
    }
    makeConfigInstanceModifiable();
    assertFalse(configModificationFailed, "Config class modification failed");
    if (originalSystemProperties == null) {
      saveProperties();
    }
    applyClassLevelConfig(context);
    if (isConfigInstanceModifiable) {
      rebuildConfig();
    }
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    restoreProperties();
    environmentVariables.clear();
    applyDeclaredConfig(context);
    if (isConfigInstanceModifiable) {
      rebuildConfig();
    }
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

      // Decide write strategy based on whether ByteBuddy retransformation actually relaxed the
      // INSTANCE fields. On HotSpot it does; on IBM J9 / OpenJ9 / Semeru it can silently fail and
      // leave the fields as private static final.
      if (isFieldModifiable(instConfigInstanceField) && isFieldModifiable(configInstanceField)) {
        writeMode = WriteMode.REFLECTION;
        instConfigInstanceField.setAccessible(true);
        configInstanceField.setAccessible(true);
      } else {
        writeMode = WriteMode.UNSAFE;
        instConfigUnsafeWriter = UnsafeFieldWriter.forStaticField(instConfigInstanceField);
        configUnsafeWriter = UnsafeFieldWriter.forStaticField(configInstanceField);
      }

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
      try {
        Object newInstConfig = instConfigConstructor.newInstance();
        Object newConfig = configConstructor.newInstance();
        if (writeMode == WriteMode.REFLECTION) {
          instConfigInstanceField.set(null, newInstConfig);
          configInstanceField.set(null, newConfig);
        } else {
          instConfigUnsafeWriter.putVolatile(newInstConfig);
          configUnsafeWriter.putVolatile(newConfig);
        }
      } catch (ReflectiveOperationException e) {
        throw new AssertionError("Failed to rebuild config", e);
      }
    }
  }

  private static boolean isFieldModifiable(Field field) {
    int mods = field.getModifiers();
    return Modifier.isPublic(mods)
        && Modifier.isStatic(mods)
        && Modifier.isVolatile(mods)
        && !Modifier.isFinal(mods);
  }

  /**
   * Encapsulates {@code sun.misc.Unsafe}-based volatile writes to a single static field. Used as
   * the fallback when ByteBuddy retransformation does not relax the field modifiers (IBM J9 /
   * OpenJ9 / Semeru).
   *
   * <p>{@code sun.misc.Unsafe} is accessed entirely via reflection so the module can keep the
   * default {@code --release} compile setting (the internal {@code sun.misc} package would
   * otherwise be off-limits to the compiler).
   */
  @SuppressForbidden
  private static final class UnsafeFieldWriter {
    private static Object unsafe;
    private static Method staticFieldBase;
    private static Method staticFieldOffset;
    private static Method putObjectVolatile;

    private final Object base;
    private final long offset;

    private UnsafeFieldWriter(Object base, long offset) {
      this.base = base;
      this.offset = offset;
    }

    /**
     * @throws ReflectiveOperationException if {@code sun.misc.Unsafe} or one of the required
     *     methods is unavailable on this JVM. Lets the caller mark config modification as failed
     *     and surface a clean test failure instead of a {@link ExceptionInInitializerError}.
     */
    static UnsafeFieldWriter forStaticField(Field staticField) throws ReflectiveOperationException {
      ensureInitialized();
      Object fieldBase = staticFieldBase.invoke(unsafe, staticField);
      long fieldOffset = (long) staticFieldOffset.invoke(unsafe, staticField);
      return new UnsafeFieldWriter(fieldBase, fieldOffset);
    }

    void putVolatile(Object value) {
      try {
        putObjectVolatile.invoke(unsafe, base, offset, value);
      } catch (ReflectiveOperationException e) {
        throw new AssertionError("Failed to write static field via Unsafe", e);
      }
    }

    private static synchronized void ensureInitialized() throws ReflectiveOperationException {
      if (unsafe != null) {
        return;
      }
      Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
      Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
      theUnsafe.setAccessible(true);
      Object instance = theUnsafe.get(null);
      staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class);
      staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
      putObjectVolatile =
          unsafeClass.getMethod("putObjectVolatile", Object.class, long.class, Object.class);
      // Publish `unsafe` last so a partially-initialized state can't fool the early-return guard.
      unsafe = instance;
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
      if (INST_CONFIG.equals(typeName) || CONFIG.equals(typeName)) {
        // Note: this only marks failure for ByteBuddy errors that surface as listener errors.
        // Silent retransformation failures (IBM J9 / Semeru) are detected later in
        // makeConfigInstanceModifiable() by inspecting the actual field modifiers.
        configModificationFailed = true;
      }
    }
  }
}
