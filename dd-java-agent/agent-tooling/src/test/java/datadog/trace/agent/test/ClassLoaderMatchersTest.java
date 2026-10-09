package datadog.trace.agent.test;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.canSkipClassLoaderByName;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.incompatibleClassLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.DatadogClassLoader;
import datadog.trace.bootstrap.instrumentation.log.LogContextScopeListener;
import datadog.trace.test.util.DDJavaSpecification;
import java.net.URL;
import java.net.URLClassLoader;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ClassLoaderMatchersTest extends DDJavaSpecification {

  @Test
  void skipNonDelegatingClassLoader() {
    ClassLoader badLoader = new NonDelegatingClassLoader();

    assertTrue(incompatibleClassLoader(badLoader));
  }

  @Test
  void skipsAgentClassLoader() {
    ClassLoader agentLoader = new DatadogClassLoader();

    assertTrue(incompatibleClassLoader(agentLoader));
  }

  @Test
  void doesNotSkipEmptyClassLoader() {
    ClassLoader emptyLoader = new ClassLoader() {};

    assertFalse(incompatibleClassLoader(emptyLoader));
  }

  @Test
  void doesNotSkipBootstrapClassLoader() {
    assertFalse(incompatibleClassLoader(null));
  }

  @Test
  void datadogClassLoaderClassNameIsHardcodedInClassLoaderMatcher() {
    assertEquals("datadog.trace.bootstrap.DatadogClassLoader", DatadogClassLoader.class.getName());
  }

  @Test
  void helperClassNamesAreHardcodedInLogInstrumentations() {
    assertEquals(
        "datadog.trace.bootstrap.instrumentation.log.LogContextScopeListener",
        LogContextScopeListener.class.getName());
  }

  @TableTest({
    "scenario             | loaderName                                                                           ",
    "core package         | 'org.drools.core.rule.PackageClassLoader'                                            ",
    "wiring package       | 'org.drools.wiring.dynamic.PackageClassLoader'                                       ",
    "java dialect package | 'org.drools.core.rule.JavaDialectRuntimeData$PackageClassLoader'                     ",
    "internal types       | 'org.drools.wiring.dynamic.DynamicProjectClassLoader$DefaultInternalTypesClassLoader'"
  })
  void skipsDroolsClassLoader(String loaderName) throws Exception {
    assertTrue(canSkipClassLoaderByName(classLoaderNamed(loaderName)));
  }

  @Test
  void skipsGosuSingleServingGosuClassLoader() throws Exception {
    ClassLoader loader = classLoaderNamed("gw.internal.gosu.compiler.SingleServingGosuClassLoader");

    assertTrue(canSkipClassLoaderByName(loader));
  }

  private static ClassLoader classLoaderNamed(String loaderName) throws Exception {
    ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
    return new ByteBuddy()
        .subclass(ClassLoader.class)
        .name(loaderName)
        .make()
        .load(systemLoader, ClassLoadingStrategy.Default.WRAPPER)
        .getLoaded()
        .getDeclaredConstructor(ClassLoader.class)
        .newInstance(systemLoader);
  }

  /** A URLClassloader which only delegates java.* classes */
  private static class NonDelegatingClassLoader extends URLClassLoader {
    NonDelegatingClassLoader() {
      super(new URL[0], (ClassLoader) null);
    }

    @Override
    public Class<?> loadClass(String className) throws ClassNotFoundException {
      if (className.startsWith("java.")) {
        return super.loadClass(className);
      }
      throw new ClassNotFoundException(className);
    }
  }
}
