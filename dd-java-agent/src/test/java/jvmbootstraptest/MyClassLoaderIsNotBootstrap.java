package jvmbootstraptest;

public class MyClassLoaderIsNotBootstrap {
  public static void main(final String[] args) {
    if (MyClassLoaderIsNotBootstrap.class.getClassLoader() == null) {
      throw new RuntimeException("Application level class was loaded by bootstrap classloader");
    }
  }
}
