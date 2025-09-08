package test.boot

import org.apache.catalina.connector.Connector
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.embedded.EmbeddedServletContainerFactory
import org.springframework.boot.context.embedded.tomcat.TomcatConnectorCustomizer
import org.springframework.boot.context.embedded.tomcat.TomcatEmbeddedServletContainerFactory
import org.springframework.boot.web.filter.OrderedRequestContextFilter
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.web.filter.RequestContextFilter
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter
import org.springframework.web.util.UrlPathHelper

// Component scan defeats the purpose of configuring with specific classes
@SpringBootApplication(scanBasePackages = "doesnotexist")
class AppConfig extends WebMvcConfigurerAdapter {

  @Bean
  EmbeddedServletContainerFactory servletContainerFactory() {
    def factory = new TomcatEmbeddedServletContainerFactory()

    factory.addConnectorCustomizers(
      new TomcatConnectorCustomizer() {
        @Override
        void customize(final Connector connector) {
          connector.setEnableLookups(true)
        }
      })

    return factory
  }

  @Override
  void configurePathMatch(PathMatchConfigurer configurer) {
    configurer.urlPathHelper = new UrlPathHelper(
      removeSemicolonContent: false
      )
  }

  // Replace the default request context filter so that it has higher precedence
  // and that the following events are correctly ordered:
  // * server attributes are registered by the RequestContextFilter. HttpServletRequest and
  //   HttpServletResponse are available on thread locals
  // * HiddenHttpMethodFilter tries to read the parameter _method
  // * org.apache.tomcat.util.http.Parameters.processParameters, an instrumented method, is called
  // * the instrumentation gives instructions to block
  // * the BlockResponseFunction is called. The BlockResponseFunction has access to the server attributes
  @Bean
  RequestContextFilter requestContextFilter() {
    new OrderedRequestContextFilter(order: Ordered.HIGHEST_PRECEDENCE)
  }
}
