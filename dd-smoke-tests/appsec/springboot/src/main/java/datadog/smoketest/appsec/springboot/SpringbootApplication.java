package datadog.smoketest.appsec.springboot;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SpringbootApplication {

  public static void main(final String[] args) {
    try {
      activateAppSec();
    } catch (Exception e) {
      System.out.println("Could not activate appSec: " + e.getMessage());
    }

    SpringApplication.run(SpringbootApplication.class, args);
    System.out.println("Started in " + ManagementFactory.getRuntimeMXBean().getUptime() + "ms");
  }

  private static void activateAppSec() throws Exception {
    Class<?> agentClass =
        ClassLoader.getSystemClassLoader().loadClass("datadog.trace.bootstrap.Agent");
    Field appSecClassLoaderField = agentClass.getDeclaredField("AGENT_CLASSLOADER");
    appSecClassLoaderField.setAccessible(true);
    ClassLoader appSecClassLoader = (ClassLoader) appSecClassLoaderField.get(null);
    Class<?> appSecSystemClass = appSecClassLoader.loadClass("com.datadog.appsec.AppSecSystem");
    Field activeField = appSecSystemClass.getField("ACTIVE");
    boolean curActiveValue = (boolean) activeField.get(null);
    if (curActiveValue) {
      System.out.println("AppSec is already active");
    } else {
      activeField.set(null, true);
      System.out.println("AppSec Activated");
    }
  }
}
