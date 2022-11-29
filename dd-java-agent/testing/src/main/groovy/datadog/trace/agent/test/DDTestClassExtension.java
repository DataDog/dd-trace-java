package datadog.trace.agent.test;

import com.google.auto.service.AutoService;
import com.google.common.reflect.ClassPath;
import datadog.trace.agent.test.utils.ClasspathUtils;
import datadog.trace.bootstrap.BootstrapProxy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstanceFactory;
import org.junit.jupiter.api.extension.TestInstanceFactoryContext;
import org.junit.jupiter.api.extension.TestInstantiationException;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;

/**
 * Prepares JUnit 5 (or Spock 2) environment in an agent-friendly way.
 *
 * <ul>
 *   <li>Adds agent bootstrap classes to bootstrap classpath.
 * </ul>
 */
@AutoService({TestInstanceFactory.class, BeforeTestExecutionCallback.class, AfterTestExecutionCallback.class})
public class DDTestClassExtension implements TestInstanceFactory, BeforeTestExecutionCallback, AfterTestExecutionCallback {

  /**
   * An exact copy of {@link datadog.trace.bootstrap.Constants#BOOTSTRAP_PACKAGE_PREFIXES}.
   *
   * <p>This list is needed to initialize the bootstrap classpath because Utils' static initializer
   * references bootstrap classes (e.g. DatadogClassLoader).
   */
  public static final String[] BOOTSTRAP_PACKAGE_PREFIXES_COPY = {
      "datadog.slf4j",
      "datadog.trace.api",
      "datadog.trace.bootstrap",
      "datadog.trace.context",
      "datadog.trace.instrumentation.api",
      "datadog.trace.logging",
      "datadog.trace.util",
  };

  private static final String[] TEST_BOOTSTRAP_PREFIXES;

  private static final String CUSTOM_CLASS_LOADER_KEY = "DD_CUSTOM_CLASS_LOADER";

  private static final String ORIGINAL_CLASS_LOADER_KEY = "DD_ORIGINAL_CLASS_LOADER";

  static {
    ByteBuddyAgent.install();
    final String[] testBS = {
        "org.slf4j",
        "ch.qos.logback",
        // Tomcat's servlet classes must be on boostrap
        // when running tomcat test
        "javax.servlet.ServletContainerInitializer",
        "javax.servlet.ServletContext"
    };
    TEST_BOOTSTRAP_PREFIXES =
        Arrays.copyOf(
            BOOTSTRAP_PACKAGE_PREFIXES_COPY,
            BOOTSTRAP_PACKAGE_PREFIXES_COPY.length + testBS.length);
    for (int i = 0; i < testBS.length; ++i) {
      TEST_BOOTSTRAP_PREFIXES[i + BOOTSTRAP_PACKAGE_PREFIXES_COPY.length] = testBS[i];
    }

    setupBootstrapClasspath();
  }

  @Override
  public Object createTestInstance(final TestInstanceFactoryContext factoryCtx, final ExtensionContext extCtx) throws TestInstantiationException {
    final Class<?> clazz = factoryCtx.getTestClass();
    final Class<?> shadowClass = shadowTestClass(factoryCtx.getTestClass());
    assertNoBootstrapClassesInTestClass(clazz);

    // Save our custom class loader for later use during test runs.
    getStore(extCtx).put(CUSTOM_CLASS_LOADER_KEY, shadowClass.getClassLoader());

    try {
      return clazz.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException ex) {
      throw new TestInstantiationException("Failed to create test class", ex);
    }
  }

  private ExtensionContext.Store getStore(final ExtensionContext ctx) {
    final ExtensionContext.Namespace ns = ExtensionContext.Namespace.create(ctx.getTestClass().get());
    return ctx.getStore(ns);
  }

  @Override
  public void beforeTestExecution(final ExtensionContext ctx) throws Exception {
    final ClassLoader customClassLoader = (ClassLoader) getStore(ctx).get(CUSTOM_CLASS_LOADER_KEY);
    if (customClassLoader == null) {
      // TODO
      throw new Exception("BAD");
    }
    final Thread currentThread = Thread.currentThread();
    final ClassLoader originalClassLoader = currentThread.getContextClassLoader();
    getStore(ctx).put(ORIGINAL_CLASS_LOADER_KEY, originalClassLoader);
    currentThread.setContextClassLoader(customClassLoader);
  }

  @Override
  public void afterTestExecution(final ExtensionContext ctx) throws Exception {
    final ClassLoader originalContextLoader = (ClassLoader) getStore(ctx).get(ORIGINAL_CLASS_LOADER_KEY);
    if (originalContextLoader == null) {
      // TODO
      throw new Exception("BAD");
    }
    Thread.currentThread().setContextClassLoader(originalContextLoader);
  }

  private static void assertNoBootstrapClassesInTestClass(final Class<?> testClass) {
    for (final Field field : testClass.getDeclaredFields()) {
      assertNotBootstrapClass(testClass, field.getType());
    }

    for (final Method method : testClass.getDeclaredMethods()) {
      assertNotBootstrapClass(testClass, method.getReturnType());
      for (final Class paramType : method.getParameterTypes()) {
        assertNotBootstrapClass(testClass, paramType);
      }
    }
  }

  private static void assertNotBootstrapClass(final Class<?> testClass, final Class<?> clazz) {
    if ((!clazz.isPrimitive()) && isBootstrapClass(clazz.getName())) {
      throw new IllegalStateException(
          testClass.getName()
              + ": Bootstrap classes are not allowed in test class field or method signatures. Offending class: "
              + clazz.getName());
    }
  }

  private static boolean isBootstrapClass(final String className) {
    for (int i = 0; i < TEST_BOOTSTRAP_PREFIXES.length; ++i) {
      if (className.startsWith(TEST_BOOTSTRAP_PREFIXES[i])) {
        return true;
      }
    }
    return false;
  }

  // Shadow the test class with bytes loaded by InstrumentationClassLoader
  private static Class<?> shadowTestClass(final Class<?> clazz) {
    try {
      final InstrumentationClassLoader customLoader =
          new InstrumentationClassLoader(DDTestClassExtension.class.getClassLoader(), clazz.getName());
      return customLoader.shadow(clazz);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void setupBootstrapClasspath() {
    // Ensure there weren't any bootstrap classes loaded prematurely.
    Set<String> prematureBootstrapClasses = new TreeSet<>();
    for (Class clazz : ByteBuddyAgent.getInstrumentation().getAllLoadedClasses()) {
      if (isBootstrapClass(clazz.getName())
          && clazz.getClassLoader() != null
          && !clazz.getName().equals("datadog.trace.api.DisableTestTrace")
          && !clazz.getName().startsWith("org.slf4j")) {
        prematureBootstrapClasses.add(clazz.getName());
      }
    }
    if (!prematureBootstrapClasses.isEmpty()) {
      throw new AssertionError(
          prematureBootstrapClasses.size()
              + " classes were loaded before bootstrap classpath was initialized: "
              + prematureBootstrapClasses);
    }
    try {
      final File bootstrapJar = createBootstrapJar();
      ByteBuddyAgent.getInstrumentation()
          .appendToBootstrapClassLoaderSearch(new JarFile(bootstrapJar));
      // Utils cannot be referenced before this line, as its static initializers load bootstrap
      // classes (for example, the bootstrap proxy).
      BootstrapProxy.addBootstrapResource(bootstrapJar.toURI().toURL());
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static File createBootstrapJar() throws IOException {
    final Set<String> bootstrapClasses = new HashSet<>();
    for (final ClassPath.ClassInfo info : ClasspathUtils.getTestClasspath().getAllClasses()) {
      // if info starts with bootstrap prefix: add to bootstrap jar
      if (isBootstrapClass(info.getName())) {
        bootstrapClasses.add(info.getResourceName());
      }
    }
    return new File(
        ClasspathUtils.createJarWithClasses(
                AgentTestRunner.class.getClassLoader(), bootstrapClasses.toArray(new String[0]))
            .getFile());
  }

  /** Run test classes in a classloader which loads test classes before delegating. */
  private static class InstrumentationClassLoader extends java.lang.ClassLoader {
    final ClassLoader parent;
    final String shadowPrefix;

    public InstrumentationClassLoader(final ClassLoader parent, final String shadowPrefix) {
      super(parent);
      this.parent = parent;
      this.shadowPrefix = shadowPrefix;
    }

    /** Forcefully inject the bytes of clazz into this classloader. */
    public Class<?> shadow(final Class<?> clazz) throws IOException {
      final Class<?> loaded = findLoadedClass(clazz.getName());
      if (loaded != null && loaded.getClassLoader() == this) {
        return loaded;
      }
      final ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(clazz.getClassLoader());
      final byte[] classBytes = locator.locate(clazz.getName()).resolve();

      return defineClass(clazz.getName(), classBytes, 0, classBytes.length);
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve)
        throws ClassNotFoundException {
      synchronized (super.getClassLoadingLock(name)) {
        final Class c = findLoadedClass(name);
        if (c != null) {
          return c;
        }
        if (name.startsWith(shadowPrefix)) {
          try {
            return shadow(super.loadClass(name, resolve));
          } catch (final Exception e) {
          }
        }

        return parent.loadClass(name);
      }
    }
  }
}
