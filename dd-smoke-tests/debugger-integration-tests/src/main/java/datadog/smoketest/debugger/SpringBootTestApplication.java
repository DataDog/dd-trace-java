package datadog.smoketest.debugger;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SpringBootTestApplication {
  @SuppressForbidden
  public static void main(String[] args) {
    SpringApplication.run(SpringBootTestApplication.class, args);
    System.out.println(SpringBootTestApplication.class.getName());
  }
}
