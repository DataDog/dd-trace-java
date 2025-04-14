package datadog.smoketest.springboot;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.management.ManagementFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SpringbootApplication {

  @SuppressForbidden
  public static void main(final String[] args) {
    SpringApplication.run(SpringbootApplication.class, args);
    System.out.println("Started in " + ManagementFactory.getRuntimeMXBean().getUptime() + "ms");
  }
}
