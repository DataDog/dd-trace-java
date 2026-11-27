package test.normalizedroute;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.SecurityAutoConfiguration;

/**
 * Minimal app with the route shapes that need presence logic in the normalized route. Spring
 * Security is on the test classpath: its auto-configuration is excluded so every route is open.
 */
@SpringBootApplication(exclude = SecurityAutoConfiguration.class)
public class NormalizedRouteAppConfig {}
