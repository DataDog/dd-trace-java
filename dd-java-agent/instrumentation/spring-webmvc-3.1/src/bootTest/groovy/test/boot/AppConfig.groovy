package test.boot

import org.apache.catalina.connector.Connector
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.embedded.EmbeddedServletContainerFactory
import org.springframework.boot.context.embedded.tomcat.TomcatConnectorCustomizer
import org.springframework.boot.context.embedded.tomcat.TomcatEmbeddedServletContainerFactory
import org.springframework.context.annotation.Bean
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter
import org.springframework.web.util.UrlPathHelper

// Component scan defeats the purpose of configuring with specific classes
@SpringBootApplication(scanBasePackages = "doesnotexist")
class AppConfig extends WebMvcConfigurerAdapter {

//  @Bean
//  EmbeddedServletContainerFactory servletContainerFactory() {
//    def factory = new TomcatEmbeddedServletContainerFactory()
//
//    factory.addConnectorCustomizers(
//      new TomcatConnectorCustomizer() {
//        @Override
//        void customize(final Connector connector) {
//          connector.setEnableLookups(true)
//        }
//      })
//
//    return factory
//  }

  @Override
  void configurePathMatch(PathMatchConfigurer configurer) {
    configurer.urlPathHelper = new UrlPathHelper(
      removeSemicolonContent: false
      )
  }
}
