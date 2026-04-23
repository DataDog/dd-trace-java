package datadog.trace.agent.test

import datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers
import datadog.trace.bootstrap.instrumentation.log.LogContextScopeListener
import datadog.trace.bootstrap.DatadogClassLoader
import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileStatic
import net.bytebuddy.ByteBuddy
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy
import spock.lang.Unroll

class ClassLoaderMatchersTest extends DDSpecification {

  def "skip non-delegating classloader"() {
    setup:
    final ClassLoader badLoader = new NonDelegatingClassLoader()
    expect:
    ClassLoaderMatchers.incompatibleClassLoader(badLoader)
  }

  def "skips agent classloader"() {
    setup:
    final ClassLoader agentLoader = new DatadogClassLoader()
    expect:
    ClassLoaderMatchers.incompatibleClassLoader(agentLoader)
  }

  def "does not skip empty classloader"() {
    setup:
    final ClassLoader emptyLoader = new ClassLoader() {}
    expect:
    !ClassLoaderMatchers.incompatibleClassLoader(emptyLoader)
  }

  def "does not skip bootstrap classloader"() {
    expect:
    !ClassLoaderMatchers.incompatibleClassLoader(null)
  }

  def "DatadogClassLoader class name is hardcoded in ClassLoaderMatcher"() {
    expect:
    DatadogClassLoader.name == "datadog.trace.bootstrap.DatadogClassLoader"
  }

  def "helper class names are hardcoded in Log Instrumentations"() {
    expect:
    LogContextScopeListener.name == "datadog.trace.bootstrap.instrumentation.log.LogContextScopeListener"
  }

  @Unroll
  def "skips drools classloader: #loaderName"() {
    given:
    ClassLoader loader = new ByteBuddy()
      .subclass(ClassLoader)
      .name(loaderName)
      .make()
      .load(ClassLoader.getSystemClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
      .getLoaded()
      .getDeclaredConstructor(ClassLoader)
      .newInstance(ClassLoader.getSystemClassLoader())

    expect:
    ClassLoaderMatchers.canSkipClassLoaderByName(loader)

    where:
    loaderName << [
      "org.drools.core.common.ProjectClassLoader",
      "org.drools.core.common.ProjectClassLoader\$IBMClassLoader",
      "org.drools.core.rule.PackageClassLoader",
      "org.drools.wiring.dynamic.PackageClassLoader",
      "org.drools.core.rule.JavaDialectRuntimeData\$PackageClassLoader"
    ]
  }

  /*
   * A URLClassloader which only delegates java.* classes
   */

  // use compile static to avoid the constant pool
  // having references to java.lang.Module
  @CompileStatic
  private static class NonDelegatingClassLoader extends URLClassLoader {
    NonDelegatingClassLoader() {
      super(new URL[0], (ClassLoader) null)
    }

    @Override
    Class<?> loadClass(String className) {
      if (className.startsWith("java.")) {
        return super.loadClass(className)
      }
      throw new ClassNotFoundException(className)
    }
  }
}
