package dd.trace.instrumentation.springsecurity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;

@SpringBootApplication
@EnableGlobalMethodSecurity(securedEnabled = true)
public class AuthenticatingLdapApplication {
  public static void main(String[] args) {
    SpringApplication.run(AuthenticatingLdapApplication.class, args);
  }
}
