package datadog.trace.instrumentation.springsecurity6;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Component scan defeats the purpose of configuring with specific classes.
// ErrorMvcAutoConfiguration is excluded by name so this compiles and runs against both Spring Boot 3
// (org.springframework.boot.autoconfigure.web.servlet.error) and Spring Boot 4
// (org.springframework.boot.webmvc.autoconfigure.error). Names absent from the classpath
// are silently ignored by the auto-configuration excluder.
@SpringBootApplication(excludeName = {
  "org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration",
  "org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration"
})
public class AppConfig implements WebMvcConfigurer {
}
