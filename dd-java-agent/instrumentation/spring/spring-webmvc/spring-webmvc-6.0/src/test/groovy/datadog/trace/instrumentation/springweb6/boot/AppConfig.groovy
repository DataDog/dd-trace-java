package datadog.trace.instrumentation.springweb6.boot


import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.util.UrlPathHelper

// Exclude error MVC auto-configuration using string names so this file compiles against both Spring Boot 3 and 4
// (the class moved from o.s.b.autoconfigure.web.servlet.error to o.s.b.webmvc.autoconfigure.error in Spring Boot 4)
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
