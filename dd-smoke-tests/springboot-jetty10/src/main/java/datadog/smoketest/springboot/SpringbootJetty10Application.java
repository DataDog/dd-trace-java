package datadog.smoketest.springboot;

import java.lang.management.ManagementFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SpringbootJetty10Application extends SpringBootServletInitializer {

  @Bean
  public ConfigurableServletWebServerFactory webServerFactory() {
    return new JettyServletWebServerFactory();
  }

  public static void main(final String[] args) {
    SpringApplication.run(SpringbootJetty10Application.class, args);
    System.out.println("Started in " + ManagementFactory.getRuntimeMXBean().getUptime() + "ms");
  }
}
