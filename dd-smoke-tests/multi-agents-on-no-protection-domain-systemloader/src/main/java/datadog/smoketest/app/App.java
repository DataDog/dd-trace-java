package datadog.smoketest.app;

import datadog.smoketest.classloader.NoProtectionDomainClassLoader;

public class App {
  public static void main(String[] args) {
    ClassLoader classLoader = App.class.getClassLoader();
    if (!(classLoader instanceof NoProtectionDomainClassLoader)) {
      throw new RuntimeException(
          "You must run JVM with -Djava.system.class.loader=datadog.smoketest.classloader.NoProtectionDomainClassLoader");
    }

    System.out.println("Hello form app!");
  }
}
