package datadog.smoketest.springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootApplication
public class SpringbootApplication {

  public static void main(final String[] args) {
    if ("true".equals(System.getenv("CI"))) {
      // CircleCI provides a mongo image
      SpringApplication.run(SpringbootApplication.class, args);
    } else { // use testcontainers locally
      try (MongoDBContainer mongoDBContainer =
          new MongoDBContainer(DockerImageName.parse("mongo:4.0.10"))) {
        mongoDBContainer.start();
        SpringApplication.run(SpringbootApplication.class, args);
      }
    }
  }
}
