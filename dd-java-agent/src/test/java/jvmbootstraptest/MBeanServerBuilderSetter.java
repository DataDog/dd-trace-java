package jvmbootstraptest;

import java.lang.management.ManagementFactory;
import java.util.Objects;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

public class MBeanServerBuilderSetter {
  public static void main(final String... args) throws Exception {
    if (System.getProperty("dd.app.customjmxbuilder") != null) {
      System.out.println("dd.app.customjmxbuilder != null");

      if (Boolean.parseBoolean(System.getProperty("dd.app.customjmxbuilder"))) {
        System.setProperty(
            "javax.management.builder.initial", "jvmbootstraptest.CustomMBeanServerBuilder");
        customAssert(
            isCustomMBeanRegistered(),
            true,
            "Javaagent should not prevent setting a custom MBeanServerBuilder");
      } else {
        customAssert(
            isJmxfetchStarted(false),
            true,
            "jmxfetch should start in premain when customjmxbuilder=false.");
      }
    } else if (System.getProperty("javax.management.builder.initial") != null) {
      System.out.println("javax.management.builder.initial != null");

      if (ClassLoader.getSystemResource(
              System.getProperty("javax.management.builder.initial").replaceAll("\\.", "/")
                  + ".class")
          == null) {
        customAssert(
            isJmxfetchStarted(false),
            false,
            "jmxfetch startup must be delayed when management builder system property is present.");
        // Change back to a valid MBeanServerBuilder.
        System.setProperty(
            "javax.management.builder.initial", "jvmbootstraptest.CustomMBeanServerBuilder");
        customAssert(
            isCustomMBeanRegistered(),
            true,
            "Javaagent should not prevent setting a custom MBeanServerBuilder");
        customAssert(
            isJmxfetchStarted(true),
            true,
            "jmxfetch should start after loading MBeanServerBuilder.");
      } else {
        customAssert(
            isJmxfetchStarted(false),
            true,
            "jmxfetch should start in premain when custom MBeanServerBuilder found on classpath.");
      }
    } else {
      System.out.println("No custom MBeanServerBuilder");

      customAssert(
          isJmxfetchStarted(false),
          true,
          "jmxfetch should start in premain when no custom MBeanServerBuilder is set.");
    }
  }

  private static boolean isCustomMBeanRegistered() throws MalformedObjectNameException {
    return ManagementFactory.getPlatformMBeanServer()
        .isRegistered(new ObjectName("test:name=custom"));
  }

  private static void customAssert(
      final Object got, final Object expected, final String assertionMessage) {
    if (!Objects.equals(got, expected)) {
      throw new RuntimeException(
          "Assertion failed. Expected <" + expected + "> got <" + got + "> " + assertionMessage);
    }
  }

  private static boolean isThreadStarted(final String name, final boolean wait) {
    // Wait up to 10 seconds for thread to appear
    for (int i = 0; i < 20; i++) {
      for (final Thread thread : Thread.getAllStackTraces().keySet()) {
        if (name.equals(thread.getName())) {
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
    return false;
  }

  private static boolean isJmxfetchStarted(final boolean wait) {
    return isThreadStarted("dd-jmx-collector", wait);
  }
}
