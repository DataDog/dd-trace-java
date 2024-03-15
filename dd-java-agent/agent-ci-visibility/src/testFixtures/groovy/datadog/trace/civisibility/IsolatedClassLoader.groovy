package datadog.trace.civisibility

import org.apache.commons.io.IOUtils

import java.lang.reflect.Constructor

final class IsolatedClassLoader extends ClassLoader {

  static void run(Collection<String> isolatedPackages, Closure closure, Object... args) throws Exception {
    def closureClass = closure.class
    IsolatedClassLoader customLoader = new IsolatedClassLoader(closureClass.getClassLoader(), [closureClass.getName(), "datadog.trace.instrumentation"] + isolatedPackages)
    Class<?> shadowedClosureClass = customLoader.loadClass(closureClass.getName())
    Constructor<?> constructor = shadowedClosureClass.getConstructor(Object, Object)
    constructor.setAccessible(true)
    Closure isolatedClosure = (Closure) constructor.newInstance(closure.delegate, closure.thisObject)

    def contextClassLoader = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = customLoader
    try {
      isolatedClosure.call(args)
    } finally {
      Thread.currentThread().contextClassLoader = contextClassLoader
    }
  }

  private final ClassLoader parent
  private final Collection<String> prefixes

  IsolatedClassLoader(ClassLoader parent, Collection<String> prefixes) {
    super(parent)
    this.parent = parent != null ? parent : getSystemClassLoader()
    this.prefixes = prefixes
  }

  @Override
  Class<?> loadClass(String name) throws ClassNotFoundException {
    try {
      for (String prefix : prefixes) {
        if (name.startsWith(prefix)) {
          def shadowedClass = findClass(name)
          if (shadowedClass != null) {
            return shadowedClass
          }
        }
      }
    } catch (Exception e) {
      throw new ClassNotFoundException("Could not load isolated class $name", e)
    }
    return super.loadClass(name, false)
  }

  @Override
  protected Class<?> findClass(String name) throws Exception {
    Class<?> loadedClass = findLoadedClass(name)
    if (loadedClass != null) {
      return loadedClass
    }

    byte[] classData = getClassData(name)
    return classData != null ? defineClass(name, classData, 0, classData.length) : null
  }

  private byte[] getClassData(String name) throws Exception {
    try (InputStream classStream = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
      return classStream != null ? IOUtils.toByteArray(classStream) : null
    }
  }
}
