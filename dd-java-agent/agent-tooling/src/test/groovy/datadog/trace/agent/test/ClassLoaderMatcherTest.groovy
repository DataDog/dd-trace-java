package datadog.trace.agent.test

import datadog.trace.agent.tooling.ClassLoaderMatcher
import datadog.trace.agent.tooling.log.LogContextScopeListener
import datadog.trace.bootstrap.DatadogClassLoader
import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileStatic

class ClassLoaderMatcherTest extends DDSpecification {

  def "skip non-delegating classloader"() {
    setup:
    final URLClassLoader badLoader = new NonDelegatingClassLoader()
    expect:
    ClassLoaderMatcher.skipClassLoader().matches(badLoader)
  }

  def "skips agent classloader"() {
    setup:
    URL root = new URL("file://")
    final URLClassLoader agentLoader = new DatadogClassLoader(root, null, new DatadogClassLoader.BootstrapClassLoaderProxy(), null)
    expect:
    ClassLoaderMatcher.skipClassLoader().matches(agentLoader)
  }

  def "does not skip empty classloader"() {
    setup:
    final ClassLoader emptyLoader = new ClassLoader() {}
    expect:
    !ClassLoaderMatcher.skipClassLoader().matches(emptyLoader)
  }

  def "does not skip bootstrap classloader"() {
    expect:
    !ClassLoaderMatcher.skipClassLoader().matches(null)
  }

  def "DatadogClassLoader class name is hardcoded in ClassLoaderMatcher"() {
    expect:
    DatadogClassLoader.name == "datadog.trace.bootstrap.DatadogClassLoader"
  }

  def "helper class names are hardcoded in Log Instrumentations"() {
    expect:
    LogContextScopeListener.name == "datadog.trace.agent.tooling.log.LogContextScopeListener"
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
