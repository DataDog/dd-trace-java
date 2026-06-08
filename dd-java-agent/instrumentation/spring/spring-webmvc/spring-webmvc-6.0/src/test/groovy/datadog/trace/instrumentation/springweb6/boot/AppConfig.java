package datadog.trace.instrumentation.springweb6.boot;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UrlPathHelper;

/**
 * Uses excludeName instead of exclude so this compiles and runs against both Spring Boot 3
 * (org.springframework.boot.autoconfigure.web.servlet.error) and Spring Boot 4
 * (org.springframework.boot.webmvc.autoconfigure.error). Names absent from the classpath
 * are silently ignored by the auto-configuration excluder.
 */
@SpringBootApplication(excludeName = {
  "org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration",
  "org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration"
})
public class AppConfig implements WebMvcConfigurer {

  @Override
  public void configurePathMatch(PathMatchConfigurer configurer) {
    UrlPathHelper urlPathHelper = new UrlPathHelper();
    urlPathHelper.setRemoveSemicolonContent(false);
    configurer.setUrlPathHelper(urlPathHelper);
  }
}
