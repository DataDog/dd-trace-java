package com.datadog.iast.test;

import static net.bytebuddy.description.modifier.FieldManifestation.VOLATILE;
import static net.bytebuddy.description.modifier.Ownership.STATIC;
import static net.bytebuddy.description.modifier.Visibility.PUBLIC;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.none;

import datadog.trace.agent.tooling.AgentInstaller;
import datadog.trace.agent.tooling.Instrumenter;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Properties;
import java.util.ServiceLoader;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.Transformer;
import net.bytebuddy.utility.JavaModule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.contrib.java.lang.system.EnvironmentVariables;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class IastJavaAgentTestRunner extends AgentBuilder.Listener.Adapter {

  private static final Logger LOGGER = LoggerFactory.getLogger(IastJavaAgentTestRunner.class);

  protected static final Instrumentation INSTRUMENTATION = ByteBuddyAgent.install();
  private static final String INST_CONFIG = "datadog.trace.api.InstrumenterConfig";
  private static final String CONFIG = "datadog.trace.api.Config";
  private static Field instConfigInstanceField;
  private static Constructor<?> instConfigConstructor;
  private static Field configInstanceField;
  private static Constructor<?> configConstructor;
  private static Properties originalSystemProperties;
  private static boolean isConfigInstanceModifiable = false;
  private static boolean configModificationFailed = false;

  @ClassRule
  public static final ReseteableEnvironmentVariables environmentVariables =
      new ReseteableEnvironmentVariables();

  static {
    installConfigTransformer();
    makeConfigInstanceModifiable();
  }

  private ResettableClassFileTransformer activeTransformer;

  @BeforeAll
  public static void setupSpec() {
    saveProperties();
  }

  @AfterAll
  public static void cleanUpSpec() {
    restoreProperties();
    if (isConfigInstanceModifiable) {
      rebuildConfig();
    }
  }

  @Before
  public void setup() {
    injectSysConfig("dd.iast.enabled", "true");
    installAgent();
  }

  @After
  public void cleanUp() {
    cleanUpAgent();
  }

  protected void installAgent() {
    assert ServiceLoader.load(Instrumenter.class, IastJavaAgentTestRunner.class.getClassLoader())
            .iterator()
            .hasNext()
        : "No instrumentation found";
    activeTransformer =
        (ResettableClassFileTransformer)
            AgentInstaller.installBytebuddyAgent(
                INSTRUMENTATION, true, AgentInstaller.getEnabledSystems(), this);
  }

  protected void cleanUpAgent() {
    activeTransformer.reset(INSTRUMENTATION, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
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
    environmentVariables.reset();
  }

  @SuppressForbidden
  private static void makeConfigInstanceModifiable() {
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
    } catch (Throwable e) {
      configModificationFailed = true;
      LOGGER.error("Failed to make config instance modifiable", e);
    }
  }

  @SuppressForbidden
  private static void installConfigTransformer() {
    new AgentBuilder.Default()
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        .with(AgentBuilder.RedefinitionStrategy.Listener.ErrorEscalating.FAIL_FAST)
        // Config is injected into the bootstrap, so we need to provide a locator.
        .with(
            new AgentBuilder.LocationStrategy.Simple(
                ClassFileLocator.ForClassLoader.ofSystemLoader()))
        .ignore(none()) // Allow transforming bootstrap classes
        .type(namedOneOf(INST_CONFIG, CONFIG))
        .transform(
            (builder, typeDescription, classLoader, module, pd) ->
                builder
                    .field(named("INSTANCE"))
                    .transform(Transformer.ForField.withModifiers(PUBLIC, STATIC, VOLATILE)))
        .with(
            new AgentBuilder.Listener.Adapter() {
              @Override
              public void onError(
                  String typeName,
                  ClassLoader classLoader,
                  JavaModule module,
                  boolean loaded,
                  Throwable throwable) {
                if (typeName.equals(CONFIG)) {
                  configModificationFailed = true;
                }
              }
            })
        .installOn(INSTRUMENTATION);
  }

  void injectSysConfig(String name, String value) {
    injectSysConfig(name, value, true);
  }

  void injectSysConfig(String name, String value, boolean addPrefix) {
    checkConfigTransformation();
    String prefixedName = name.startsWith("dd.") || !addPrefix ? name : "dd." + name;
    System.setProperty(prefixedName, value);
    rebuildConfig();
  }

  void removeSysConfig(String name) {
    removeSysConfig(name, true);
  }

  void removeSysConfig(String name, boolean addPrefix) {
    checkConfigTransformation();

    String prefixedName = name.startsWith("dd.") || !addPrefix ? name : "dd." + name;
    System.clearProperty(prefixedName);
    rebuildConfig();
  }

  void injectEnvConfig(String name, String value) {
    injectEnvConfig(name, value, true);
  }

  void injectEnvConfig(String name, String value, boolean addPrefix) {
    checkConfigTransformation();

    String prefixedName = name.startsWith("DD_") || !addPrefix ? name : "DD_" + name;
    environmentVariables.set(prefixedName, value);
    rebuildConfig();
  }

  void removeEnvConfig(String name) {
    removeEnvConfig(name, true);
  }

  void removeEnvConfig(String name, boolean addPrefix) {
    checkConfigTransformation();

    String prefixedName = name.startsWith("DD_") || !addPrefix ? name : "DD_" + name;
    environmentVariables.clear(prefixedName);
    rebuildConfig();
  }

  /**
   * Reset the global configuration. Please note that Runtime ID is preserved to the pre-existing
   * value.
   */
  static synchronized void rebuildConfig() {
    checkConfigTransformation();
    try {
      final Object newInstConfig = instConfigConstructor.newInstance();
      instConfigInstanceField.set(null, newInstConfig);
      final Object newConfig = configConstructor.newInstance();
      configInstanceField.set(null, newConfig);
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void checkConfigTransformation() {
    // Ensure the class was re-transformed properly in
    // makeConfigInstanceModifiable()
    assert isConfigInstanceModifiable;
    assert instConfigConstructor != null;
    checkWritable(instConfigInstanceField);
    assert configConstructor != null;
    checkWritable(configInstanceField);
  }

  private static void checkWritable(Field field) {
    assert field != null;
    assert Modifier.isPublic(field.getModifiers());
    assert Modifier.isStatic(field.getModifiers());
    assert Modifier.isVolatile(field.getModifiers());
    assert !Modifier.isFinal(field.getModifiers());
  }

  private static class ReseteableEnvironmentVariables extends EnvironmentVariables {

    private static final Method METHOD;

    static {
      try {
        final Class<?> clazz =
            Thread.currentThread()
                .getContextClassLoader()
                .loadClass(EnvironmentVariables.class.getName() + "$EnvironmentVariablesStatement");
        METHOD = clazz.getDeclaredMethod("restoreOriginalVariables");
        METHOD.setAccessible(true);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    private Statement statement;

    @Override
    public Statement apply(Statement base, Description description) {
      statement = base;
      return super.apply(base, description);
    }

    public void reset() {
      if (statement != null) {
        try {
          METHOD.invoke(statement);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }
  }
}
