package datadog.smoketest.springboot;

import datadog.smoketest.springboot.filter.SessionVisitorFilter;
import javax.servlet.Filter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SpringbootApplication {

  @Bean
  public Filter badBehaviorFilter() {
    return new SessionVisitorFilter();
  }

  public static void main(final String[] args) {
    SpringApplication.run(SpringbootApplication.class, args);
  }
}
