package datadog.trace.agent.test;

import com.google.common.reflect.ClassPath;
import datadog.trace.agent.test.utils.ClasspathUtils;
import datadog.trace.bootstrap.BootstrapProxy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.launcher.LauncherInterceptor;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;

public class AgentBootstrapExtension implements LauncherInterceptor, BeforeAllCallback {
  /**
   * An exact copy of {@link datadog.trace.bootstrap.Constants#BOOTSTRAP_PACKAGE_PREFIXES}.
   *
   * <p>This list is needed to initialize the bootstrap classpath because Utils' static initializer
   * references bootstrap classes (e.g. DatadogClassLoader).
   */
  // TODO Need to check if it is still the case and if the duplication is still needed
  private static final String[] BOOTSTRAP_PACKAGE_PREFIXES_COPY = {
      "datadog.slf4j",
      "datadog.appsec.api",
      "datadog.trace.api",
      "datadog.trace.bootstrap",
      "datadog.trace.context",
      "datadog.trace.instrumentation.api",
      "datadog.trace.logging",
      "datadog.trace.util",
  };
  private static final String[] TEST_BOOSTRAP_PACKAGE_PREFIXES = {
      "org.slf4j",
      "ch.qos.logback",
      // Tomcat's servlet classes must be on boostrap
      // when running tomcat test
      "javax.servlet.ServletContainerInitializer",
      "javax.servlet.ServletContext",
  };

  private static final String[] BOOTSTRAP_PACKAGE_PREFIXES = new String[BOOTSTRAP_PACKAGE_PREFIXES_COPY.length + TEST_BOOSTRAP_PACKAGE_PREFIXES.length];

  static {
    ByteBuddyAgent.install();
    System.arraycopy(BOOTSTRAP_PACKAGE_PREFIXES_COPY, 0, BOOTSTRAP_PACKAGE_PREFIXES, 0, BOOTSTRAP_PACKAGE_PREFIXES_COPY.length);
    System.arraycopy(TEST_BOOSTRAP_PACKAGE_PREFIXES, 0, BOOTSTRAP_PACKAGE_PREFIXES, BOOTSTRAP_PACKAGE_PREFIXES_COPY.length, TEST_BOOSTRAP_PACKAGE_PREFIXES.length);
    verifyNoBootstrapClassLoaded();
    setupBootstrapClasspath();
  }

  private static void assertNotBootstrapClass(Class<?> testClass, Class<?> clazz) {
    if (!clazz.isPrimitive() && isBootstrapClass(clazz.getName())) {
      throw new IllegalStateException(
          testClass.getName()
              + ": Bootstrap classes are not allowed in test class field or method signatures. Offending class: "
              + clazz.getName());
    }
  }

  private static boolean isBootstrapClass(String className) {
    for (String testBootstrapPrefix : BOOTSTRAP_PACKAGE_PREFIXES) {
      if (className.startsWith(testBootstrapPrefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks that no bootstrap class are prematurely loaded.
   */
  private static void verifyNoBootstrapClassLoaded() {
    Set<String> prematureBootstrapClasses = new TreeSet<>();
    for (Class<?> clazz : ByteBuddyAgent.getInstrumentation().getAllLoadedClasses()) {
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
  }

  private static void setupBootstrapClasspath() {
    try {
      File bootstrapJar = createBootstrapJar();
      ByteBuddyAgent.getInstrumentation()
          .appendToBootstrapClassLoaderSearch(new JarFile(bootstrapJar));
      // Utils cannot be referenced before this line, as its static initializers load bootstrap
      // classes (for example, the bootstrap proxy).
      BootstrapProxy.addBootstrapResource(bootstrapJar.toURI().toURL());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static File createBootstrapJar() throws IOException {
    Set<String> bootstrapClasses = new HashSet<>();
    for (ClassPath.ClassInfo info : ClasspathUtils.getTestClasspath().getAllClasses()) {
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

  @Override
  public <T> T intercept(Invocation<T> invocation) {
    Thread currentThread = Thread.currentThread();
    ClassLoader originalClassLoader = currentThread.getContextClassLoader();
    InstrumentationClassLoader classLoader = new InstrumentationClassLoader(originalClassLoader, "");// TODO Shadow prefix
    currentThread.setContextClassLoader(classLoader);
    try {
      return invocation.proceed();
    } finally {
      currentThread.setContextClassLoader(originalClassLoader);
    }
  }

  @Override
  public void close() {
    // Nothing to clean up
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    Class<?> testClass = context.getTestClass().orElseThrow(() -> new IllegalStateException("No test class"));
    assertNoBootstrapClassesInTestClass(testClass);
  }

  /**
   * Checks the test class does not refer to a bootstrap class.
   *
   * @param testClass The test {@link Class}.
   */
  private void assertNoBootstrapClassesInTestClass(Class<?> testClass) {
    for (Field field : testClass.getDeclaredFields()) {
      assertNotBootstrapClass(testClass, field.getType());
    }
    for (Method method : testClass.getDeclaredMethods()) {
      assertNotBootstrapClass(testClass, method.getReturnType());
      for (Class<?> paramType : method.getParameterTypes()) {
        assertNotBootstrapClass(testClass, paramType);
      }
    }
  }

  private static class InstrumentationClassLoader extends ClassLoader {
    private final ClassLoader parent;
    private final String shadowPrefix;

    public InstrumentationClassLoader(ClassLoader parent, String shadowPrefix) {
      super(parent);
      this.parent = parent;
      this.shadowPrefix = shadowPrefix;
    }

    /** Forcefully inject the bytes of clazz into this classloader. */
    public Class<?> shadow(Class<?> clazz) throws IOException {
      Class<?> loaded = findLoadedClass(clazz.getName());
      if (loaded != null && loaded.getClassLoader() == this) {
        return loaded;
      }
      try (ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(clazz.getClassLoader())) {
        byte[] classBytes = locator.locate(clazz.getName()).resolve();
        return defineClass(clazz.getName(), classBytes, 0, classBytes.length);
      }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (super.getClassLoadingLock(name)) {
        Class<?> c = findLoadedClass(name);
        if (c != null) {  // TODO Does not handle resolve. Does it matter?
          return c;
        }
        if (name.startsWith(this.shadowPrefix)) {
          try {
            return shadow(super.loadClass(name, resolve));
          } catch (Exception ignored) {
          }
        }
        return this.parent.loadClass(name);
      }
    }
  }
}
