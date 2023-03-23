package datadog.smoketest.springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SpringbootApplication {
  @Bean
  public ConfigurableServletWebServerFactory webServerFactory() {
    return new JettyServletWebServerFactory();
  }

  public static void main(final String[] args) {
    SpringApplication.run(SpringbootApplication.class, args);
  }
}
