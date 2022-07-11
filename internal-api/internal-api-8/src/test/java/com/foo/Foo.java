package com.foo;

/**
 * This class is needed to create a fake stacktrace to test if
 * datadog.trace.api.sampling.util.stacktrace.StaclWalker excludes dd-trace-java packages from the
 * stack and provides the rest of the stack correctly
 */
public class Foo {

  public static void fooMethod(Runnable runnable) {
    Foo2.foo2Method(runnable);
  }

  private static class Foo2 {
    public static void foo2Method(Runnable runnable) {
      Foo3.foo3Method(runnable);
    }
  }

  private static class Foo3 {
    public static void foo3Method(Runnable runnable) {
      runnable.run();
    }
  }
}
