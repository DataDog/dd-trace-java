package jvmbootstraptest;

import static java.util.concurrent.TimeUnit.MINUTES;

import datadog.trace.test.util.GCUtils;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;

public class UnloadingChecker {
  static class Canary {}

  public static void main(final String[] args) throws Exception {
    ClassLoadingMXBean classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
    long initialUnloadCount = classLoadingMXBean.getUnloadedClassCount();

    // load an isolated class which we know can be unloaded after a full GC
    new IsolatingClassLoader().loadClass("jvmbootstraptest.UnloadingChecker$Canary");

    long waitNanos = MINUTES.toNanos(2);
    long startNanos = System.nanoTime();

    while (System.nanoTime() - startNanos < waitNanos) {
      try {
        GCUtils.awaitGC();
      } catch (Throwable ignore) {
      }
      if (initialUnloadCount < classLoadingMXBean.getUnloadedClassCount()) {
        break; // some class unloading has taken place, stop and check results
      }
    }
  }
}
