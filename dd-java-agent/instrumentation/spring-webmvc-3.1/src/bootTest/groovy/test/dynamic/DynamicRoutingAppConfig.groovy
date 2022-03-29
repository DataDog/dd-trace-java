package test.dynamic

import org.apache.catalina.connector.Connector
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.embedded.EmbeddedServletContainerFactory
import org.springframework.boot.context.embedded.tomcat.TomcatConnectorCustomizer
import org.springframework.boot.context.embedded.tomcat.TomcatEmbeddedServletContainerFactory
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpOutputMessage
import org.springframework.http.MediaType
import org.springframework.http.converter.AbstractHttpMessageConverter
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.http.converter.HttpMessageNotWritableException
import org.springframework.util.StreamUtils
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.accept.ContentNegotiationStrategy
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.filter.RequestContextFilter
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter

import java.nio.charset.StandardCharsets

@SpringBootApplication
class DynamicRoutingAppConfig extends WebMvcConfigurerAdapter {

  @Override
  void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
    configurer.favorPathExtension(false)
      .favorParameter(true)
      .ignoreAcceptHeader(true)
      .useJaf(false)
      .defaultContentTypeStrategy(new ContentNegotiationStrategy() {
        @Override
        List<MediaType> resolveMediaTypes(NativeWebRequest webRequest) throws HttpMediaTypeNotAcceptableException {
          return [MediaType.TEXT_PLAIN]
        }
      })
  }


  @Bean
  FilterRegistrationBean requestContextFilterRegistrationBean() {
    // make sure this hasn't happened when HandlerMappingResourceNameFilter executes
    FilterRegistrationBean registrationBean = new FilterRegistrationBean()
    RequestContextFilter requestContextFilter = new RequestContextFilter()
    registrationBean.setFilter(requestContextFilter)
    registrationBean.setOrder(Ordered.LOWEST_PRECEDENCE)
    return registrationBean
  }

  @Bean
  FilterRegistrationBean clearRequestContextFilterRegistrationBean() {
    // make sure context is cleared when HandlerMappingResourceNameFilter executes
    FilterRegistrationBean registrationBean = new FilterRegistrationBean()
    ClearRequestContext requestContextFilter = new ClearRequestContext()
    registrationBean.setFilter(requestContextFilter)
    registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE)
    return registrationBean
  }

  @Bean
  TestController testController() {
    return new TestController()
  }

  @Bean
  DynamicRoutingConfig routingConfig() {
    return new DynamicRoutingConfig()
  }

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

  @Bean
  HttpMessageConverter<Map<String, Object>> createPlainMapMessageConverter() {
    return new AbstractHttpMessageConverter<Map<String, Object>>(MediaType.TEXT_PLAIN) {

        @Override
        protected boolean supports(Class<?> clazz) {
          return Map.isAssignableFrom(clazz)
        }

        @Override
        protected Map<String, Object> readInternal(Class<? extends Map<String, Object>> clazz, HttpInputMessage inputMessage) throws IOException, HttpMessageNotReadableException {
          return null
        }

        @Override
        protected void writeInternal(Map<String, Object> stringObjectMap, HttpOutputMessage outputMessage) throws IOException, HttpMessageNotWritableException {
          StreamUtils.copy(stringObjectMap.get("message"), StandardCharsets.UTF_8, outputMessage.getBody())
        }
      }
  }
}
