package datadog.smoketest.systemloader;

import java.net.URLClassLoader;

public class TestLoader extends URLClassLoader {

  public TestLoader(ClassLoader parent) {
    super(TestHelper.classPath(), parent);
    try {
      // trigger loading from this class-loader before our agent is installed
      loadClass("sample.app.Resource$Test1");
      loadClass("sample.app.Resource$Test2");
      loadClass("sample.app.Resource$Test3");
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (name.startsWith("java")
        || name.startsWith("jdk")
        || name.startsWith("sun")
        || name.startsWith("com.sun")
        || name.startsWith("com.ibm")
        || name.startsWith("openj9")) {
      System.out.println("Loading " + name + " from JDK");
      return super.loadClass(name, resolve); // delegate JDK classes to boot-class-path
    } else {
      System.out.println("Loading " + name + " from TestLoader");
      return loadLocalClass(name, resolve); // otherwise only look locally for the type
    }
  }

  private Class<?> loadLocalClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      Class<?> clazz = findLoadedClass(name);
      if (null == clazz) {
        clazz = findClass(name);
      }
      if (resolve) {
        resolveClass(clazz);
      }
      return clazz;
    }
  }

  // see https://docs.oracle.com/javase/9/docs/api/java/lang/instrument/package-summary.html
  void appendToClassPathForInstrumentation(String path) {
    addURL(TestHelper.toURL(path));
  }
}
