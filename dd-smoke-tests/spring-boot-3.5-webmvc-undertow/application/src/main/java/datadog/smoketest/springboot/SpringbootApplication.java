package datadog.smoketest.springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories
public class SpringbootApplication {

  public static void main(final String[] args) {
    SpringApplication.run(SpringbootApplication.class, args);
  }
}
