package datadog.trace.instrumentation.springweb6.boot


import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.util.UrlPathHelper

// Component scan defeats the purpose of configuring with specific classes
// excludeName works across Boot 3 (autoconfigure.web.servlet.error) and Boot 4 (webmvc.autoconfigure.error)
@SpringBootApplication(excludeName = [
  'org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration',
  'org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration'
])
class AppConfig {

  /**
   * Keeps semicolon content in the lookup path, which switches request mapping to AntPathMatcher.
   * SpringBootPathPatternTest disables it to run on the default PathPatternParser.
   */
  @Configuration
  @ConditionalOnProperty(name = AppConfig.LEGACY_PATH_MATCHING, havingValue = "true", matchIfMissing = true)
  static class LegacyPathMatchingConfig implements WebMvcConfigurer {
    @Override
    void configurePathMatch(PathMatchConfigurer configurer) {
      UrlPathHelper urlPathHelper = new UrlPathHelper()
      urlPathHelper.setRemoveSemicolonContent(false)
      configurer.setUrlPathHelper(urlPathHelper)
    }
  }

  static final String LEGACY_PATH_MATCHING = "test.legacy-path-matching"
}
