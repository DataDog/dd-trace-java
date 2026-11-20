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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClassLoaderMatchersTest extends DDJavaSpecification {

  @Test
  void skipNonDelegatingClassloader() {
    ClassLoader badLoader = new NonDelegatingClassLoader();
    assertTrue(incompatibleClassLoader(badLoader));
  }

  @Test
  void skipsAgentClassloader() {
    ClassLoader agentLoader = new DatadogClassLoader();
    assertTrue(incompatibleClassLoader(agentLoader));
  }

  @Test
  void doesNotSkipEmptyClassloader() {
    ClassLoader emptyLoader = new ClassLoader() {};
    assertFalse(incompatibleClassLoader(emptyLoader));
  }

  @Test
  void doesNotSkipBootstrapClassloader() {
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

  @ParameterizedTest(name = "skips classloader: {0}")
  @ValueSource(
      strings = {
        "org.codehaus.janino.ByteArrayClassLoader",
        "org.codehaus.commons.compiler.util.reflect.ByteArrayClassLoader",
        "org.drools.core.rule.PackageClassLoader",
        "org.drools.wiring.dynamic.PackageClassLoader",
        "org.drools.core.rule.JavaDialectRuntimeData$PackageClassLoader",
        "org.drools.wiring.dynamic.DynamicProjectClassLoader$DefaultInternalTypesClassLoader"
      })
  void skipsClassloaderByName(String loaderName) throws Exception {
    ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
    ClassLoader loader =
        new ByteBuddy()
            .subclass(ClassLoader.class)
            .name(loaderName)
            .make()
            .load(systemClassLoader, ClassLoadingStrategy.Default.WRAPPER)
            .getLoaded()
            .getDeclaredConstructor(ClassLoader.class)
            .newInstance(systemClassLoader);

    assertTrue(canSkipClassLoaderByName(loader));
  }

  /** A URLClassloader which only delegates java.* classes */
  private static class NonDelegatingClassLoader extends URLClassLoader {
    NonDelegatingClassLoader() {
      super(new URL[0], null);
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
