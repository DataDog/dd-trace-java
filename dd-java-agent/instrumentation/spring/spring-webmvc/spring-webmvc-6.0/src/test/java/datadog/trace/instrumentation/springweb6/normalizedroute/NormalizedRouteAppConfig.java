package datadog.trace.instrumentation.springweb6.normalizedroute;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/** Route shapes that need presence logic, on the Boot 3 default PathPatternParser. */
@SpringBootApplication(
    exclude = {
      SecurityAutoConfiguration.class,
      SecurityFilterAutoConfiguration.class,
      UserDetailsServiceAutoConfiguration.class
    })
public class NormalizedRouteAppConfig {}
