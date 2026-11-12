package datadog.smoketest.appsec.springboot;

import java.lang.management.ManagementFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration;

@SpringBootApplication(exclude = MultipartAutoConfiguration.class)
public class SpringbootApplication {

  public static void main(final String[] args) {
    SpringApplication.run(SpringbootApplication.class, args);
    System.out.println("Started in " + ManagementFactory.getRuntimeMXBean().getUptime() + "ms");
  }
}
