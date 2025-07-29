package jvmbootstraptest;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.logging.LogManager;

public class LogManagerSetter {

  // avoid CustomLogManager.class.getName() as that could initialize JUL before we've set the logger
  private static final String CUSTOM_LOG_MANAGER_CLASS_NAME = "jvmbootstraptest.CustomLogManager";

  public static void main(final String... args) throws Exception {
    if (System.getProperty("dd.app.customlogmanager") != null) {
      System.out.println("dd.app.customlogmanager != null");

      if (Boolean.parseBoolean(System.getProperty("dd.app.customlogmanager"))) {
        System.setProperty("java.util.logging.manager", CUSTOM_LOG_MANAGER_CLASS_NAME);
        customAssert(
            LogManager.getLogManager().getClass(),
            LogManagerSetter.class
                .getClassLoader()
                .loadClass(System.getProperty("java.util.logging.manager")),
            "Javaagent should not prevent setting a custom log manager");
      } else {
        customAssert(
            isTracerInstalled(false),
            true,
            "tracer should be installed in premain when customlogmanager=false.");
        customAssert(
            isJmxfetchStarted(false),
            true,
            "jmxfetch should start in premain when customlogmanager=false.");
        if (isJFRSupported()) {
          customAssert(
              isProfilingStarted(false),
              true,
              "profiling should start in premain when customlogmanager=false.");
        }
      }
    } else if (System.getProperty("java.util.logging.manager") != null) {
      System.out.println("java.util.logging.manager != null");

      customAssert(
          isTracerInstalled(false),
          true,
          "tracer install is not delayed when log manager system property is present.");
      customAssert(
          isJmxfetchStarted(false),
          false,
          "jmxfetch startup must be delayed when log manager system property is present.");
      if (isJFRSupported()) {
        assertProfilingStartupDelayed(
            "profiling startup must be delayed when log manager system property is present.");
      }
      // Change back to a valid LogManager.
      System.setProperty("java.util.logging.manager", CUSTOM_LOG_MANAGER_CLASS_NAME);
      customAssert(
          LogManager.getLogManager().getClass(),
          LogManagerSetter.class
              .getClassLoader()
              .loadClass(System.getProperty("java.util.logging.manager")),
          "Javaagent should not prevent setting a custom log manager");
      customAssert(
          isJmxfetchStarted(true), true, "jmxfetch should start after loading LogManager.");
      if (isJFRSupported()) {
        customAssert(
            isProfilingStarted(true), true, "profiling should start after loading LogManager.");
      }
    } else if (System.getenv("JBOSS_HOME") != null) {
      System.out.println("JBOSS_HOME != null");
      customAssert(
          isTracerInstalled(false),
          true,
          "tracer install is not delayed when JBOSS_HOME property is present.");
      customAssert(
          isJmxfetchStarted(false),
          false,
          "jmxfetch startup must be delayed when JBOSS_HOME property is present.");
      if (isJFRSupported()) {
        assertProfilingStartupDelayed(
            "profiling startup must be delayed when JBOSS_HOME property is present.");
      }

      System.setProperty("java.util.logging.manager", CUSTOM_LOG_MANAGER_CLASS_NAME);
      customAssert(
          LogManager.getLogManager().getClass(),
          LogManagerSetter.class
              .getClassLoader()
              .loadClass(System.getProperty("java.util.logging.manager")),
          "Javaagent should not prevent setting a custom log manager");
      customAssert(
          isJmxfetchStarted(true),
          true,
          "jmxfetch should start after loading with JBOSS_HOME set.");
      if (isJFRSupported()) {
        customAssert(
            isProfilingStarted(true),
            true,
            "profiling should start after loading with JBOSS_HOME set.");
      }
    } else {
      System.out.println("No custom log manager");

      customAssert(
          isTracerInstalled(false),
          true,
          "tracer should be installed in premain when no custom log manager is set");
      customAssert(
          isJmxfetchStarted(false),
          true,
          "jmxfetch should start in premain when no custom log manager is set.");
      if (isJFRSupported()) {
        customAssert(
            isProfilingStarted(false),
            true,
            "profiling should start in premain when no custom log manager is set.");
      }
    }
  }

  private static void customAssert(
      final Object got, final Object expected, final String assertionMessage) {
    if (got == expected) return; // null check
    if (!got.equals(expected)) {
      throw new RuntimeException(
          "Assertion failed. Expected <" + expected + "> got <" + got + "> " + assertionMessage);
    }
  }

  private static void assertProfilingStartupDelayed(final String message) {
    if (okHttpMayIndirectlyLoadJUL()) {
      customAssert(isProfilingStarted(false), false, message);
    } else {
      customAssert(
          isProfilingStarted(false),
          true,
          "We can safely start profiler on java9+ since it doesn't indirectly trigger logger manager init");
    }
  }

  private static boolean isThreadStarted(final String name, final boolean wait) {
    System.out.println("Checking for thread " + name + "...");

    // Wait up to 10 seconds for thread to appear
    for (int i = 0; i < 20; i++) {
      for (final Thread thread : Thread.getAllStackTraces().keySet()) {
        if (name.equals(thread.getName())) {
          System.out.println("...thread " + name + " has started");
          return true;
        }
      }
      if (!wait) {
        break;
      }
      try {
        Thread.sleep(500);
      } catch (final InterruptedException e) {
        e.printStackTrace();
      }
    }
    System.out.println("...thread " + name + " has not started");
    return false;
  }

  private static boolean isJmxfetchStarted(final boolean wait) {
    return isThreadStarted("dd-jmx-collector", wait);
  }

  private static boolean isProfilingStarted(final boolean wait) {
    return isThreadStarted("dd-profiler-recording-scheduler", wait);
  }

  private static boolean isTracerInstalled(final boolean wait) {
    System.out.println("Checking for tracer...");

    // Wait up to 10 seconds for tracer to get installed
    for (int i = 0; i < 20; i++) {
      if (AgentTracer.isRegistered()) {
        System.out.println("...tracer is installed");
        return true;
      }
      if (!wait) {
        break;
      }
      try {
        Thread.sleep(500);
      } catch (final InterruptedException e) {
        e.printStackTrace();
      }
    }
    System.out.println("...tracer is not installed");
    return false;
  }

  private static boolean okHttpMayIndirectlyLoadJUL() {
    if ("IBM Corporation".equals(System.getProperty("java.vm.vendor"))) {
      return true; // IBM JDKs ship with 'IBMSASL' which will load JUL when OkHttp accesses TLS
    }
    if (!System.getProperty("java.version").startsWith("1.")) {
      return false; // JDKs since 9 have reworked JFR to use a different logging facility, not JUL
    }
    return isJFRSupported(); // assume OkHttp will indirectly load JUL via its JFR events
  }

  private static boolean isJFRSupported() {
    return Thread.currentThread().getContextClassLoader().getResource("jdk/jfr/Recording.class")
        != null;
  }
}
