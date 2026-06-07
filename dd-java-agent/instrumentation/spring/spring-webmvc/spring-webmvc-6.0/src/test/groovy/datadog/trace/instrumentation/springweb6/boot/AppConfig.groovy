package datadog.trace.instrumentation.springweb6.boot


import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.util.UrlPathHelper

// Component scan defeats the purpose of configuring with specific classes.
// ErrorMvcAutoConfiguration is referenced by name to stay compatible with both Spring Boot 3
// (org.springframework.boot.autoconfigure.web.servlet.error) and Spring Boot 4
// (org.springframework.boot.webmvc.autoconfigure.error), which relocated the class. Names that
// are not present on the classpath are silently ignored by the auto-configuration excluder.
@SpringBootApplication(excludeName = [
  "org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration",
  "org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration"
])
class AppConfig implements WebMvcConfigurer {

  @Override
  void configurePathMatch(PathMatchConfigurer configurer) {
    UrlPathHelper urlPathHelper = new UrlPathHelper()
    urlPathHelper.setRemoveSemicolonContent(false)
    configurer.setUrlPathHelper(urlPathHelper)
  }
}
