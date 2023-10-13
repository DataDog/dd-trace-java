package datadog.trace.instrumentation.springweb6.boot


import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.util.UrlPathHelper

// Component scan defeats the purpose of configuring with specific classes
@SpringBootApplication(exclude = [ErrorMvcAutoConfiguration])
class AppConfig implements WebMvcConfigurer {

  @Override
  void configurePathMatch(PathMatchConfigurer configurer) {
    UrlPathHelper urlPathHelper = new UrlPathHelper()
    urlPathHelper.setRemoveSemicolonContent(false)
    configurer.setUrlPathHelper(urlPathHelper)
  }
}
