package jvmbootstraptest;

public class IsolatedClassloading {
  public static void main(final String[] args) throws Exception {

    // isolate an isolating classloader and then use that to try and load the target class
    final ClassLoader isolatedLoader =
        (ClassLoader)
            new IsolatingClassLoader()
                .loadClass(IsolatingClassLoader.class.getName())
                .newInstance();

    isolatedLoader.loadClass(Target.class.getName());
  }

  interface Target {}
}
