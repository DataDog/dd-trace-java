package test.dynamic

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport

@Configuration
class HandlerMappingConfiguration extends WebMvcConfigurationSupport {

  @Bean
  @Override
  HandlerMapping viewControllerHandlerMapping() {
    return new DynamicHandlerMapping()
  }
}
