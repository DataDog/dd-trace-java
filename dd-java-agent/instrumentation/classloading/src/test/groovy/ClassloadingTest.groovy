import datadog.trace.agent.test.AgentTestRunner

class ClassloadingTest extends AgentTestRunner {
  def "delegates to bootstrap class loader for agent classes"() {
    setup:
    def classLoader = new NonDelegatingURLClassLoader()

    when:
    Class<?> clazz
    try {
      clazz = Class.forName("datadog.trace.api.GlobalTracer", false, classLoader)
    } catch (ClassNotFoundException e) {
    }

    then:
    assert clazz != null
    assert clazz.getClassLoader() == null
  }

  static class NonDelegatingURLClassLoader extends URLClassLoader {

    NonDelegatingURLClassLoader() {
      super(new URL[0])
    }

    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        Class<?> clazz = findLoadedClass(name)
        if (clazz == null) {
          clazz = findClass(name)
        }
        if (resolve) {
          resolveClass(clazz)
        }
        return clazz
      }
    }
  }
}
