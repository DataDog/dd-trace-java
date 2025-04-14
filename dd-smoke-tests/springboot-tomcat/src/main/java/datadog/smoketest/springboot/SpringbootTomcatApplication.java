package datadog.smoketest.springboot;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.management.ManagementFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

@SpringBootApplication
public class SpringbootTomcatApplication extends SpringBootServletInitializer {

  @SuppressForbidden
  public static void main(final String[] args) {
    SpringApplication.run(SpringbootTomcatApplication.class, args);
    System.out.println("Started in " + ManagementFactory.getRuntimeMXBean().getUptime() + "ms");
  }
}
