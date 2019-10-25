package dd.trace.instrumentation.springsecurity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AuthenticatingLdapApplication {

  public static void main(String[] args) {
    SpringApplication.run(AuthenticatingLdapApplication.class, args);
  }
}
