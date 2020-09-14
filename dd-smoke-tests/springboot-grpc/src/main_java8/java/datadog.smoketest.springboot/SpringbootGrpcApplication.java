package datadog.smoketest.springboot;

import java.lang.management.ManagementFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class SpringbootGrpcApplication {

  public static void main(final String[] args) {
    ConfigurableApplicationContext app =
        SpringApplication.run(SpringbootGrpcApplication.class, args);
    Integer port = app.getBean("local.server.port", Integer.class);
    System.out.println(
        "Bound to " + port + " in " + ManagementFactory.getRuntimeMXBean().getUptime() + "ms");
  }
}
