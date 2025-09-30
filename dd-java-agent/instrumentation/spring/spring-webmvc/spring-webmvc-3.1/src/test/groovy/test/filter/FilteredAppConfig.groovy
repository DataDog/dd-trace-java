package test.filter

import com.google.common.base.Charsets
import datadog.trace.agent.test.base.HttpServerTest
import org.apache.catalina.connector.Connector
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.embedded.EmbeddedServletContainerFactory
import org.springframework.boot.context.embedded.tomcat.TomcatConnectorCustomizer
import org.springframework.boot.context.embedded.tomcat.TomcatEmbeddedServletContainerFactory
import org.springframework.context.annotation.Bean
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
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter

import javax.servlet.Filter
import javax.servlet.FilterChain
import javax.servlet.FilterConfig
import javax.servlet.ServletException
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

@SpringBootApplication
class FilteredAppConfig extends WebMvcConfigurerAdapter {

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
          StreamUtils.copy(stringObjectMap.get("message"), Charsets.UTF_8, outputMessage.getBody())
        }
      }
  }

  @Bean
  Filter servletFilter() {
    return new Filter() {

        @Override
        void init(FilterConfig filterConfig) throws ServletException {
        }

        @Override
        void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
          HttpServletRequest req = (HttpServletRequest) request
          HttpServletResponse resp = (HttpServletResponse) response
          HttpServerTest.ServerEndpoint endpoint = HttpServerTest.ServerEndpoint.forPath(req.servletPath)
          HttpServerTest.controller(endpoint) {
            resp.contentType = "text/plain"
            switch (endpoint) {
              case SUCCESS:
                resp.status = endpoint.status
                resp.writer.print(endpoint.body)
                break
              case FORWARDED:
                resp.status = endpoint.status
                resp.writer.print(req.getHeader("x-forwarded-for"))
                break
              case BODY_URLENCODED:
                resp.status = endpoint.status
                resp.writer.print(req.parameterMap.findAll { it.key != 'ignore' } as String)
                break
              case QUERY_ENCODED_BOTH:
              case QUERY_ENCODED_QUERY:
              case QUERY_PARAM:
                resp.status = endpoint.status
                resp.writer.print(endpoint.query)
                break
              case PATH_PARAM:
                resp.status = endpoint.status
                resp.writer.print(endpoint.body)
                break
              case REDIRECT:
                resp.sendRedirect(endpoint.body)
                break
              case ERROR:
                resp.sendError(endpoint.status, endpoint.body)
                break
              case EXCEPTION:
                throw new Exception(endpoint.body)
              default:
                chain.doFilter(request, response)
            }
          }
        }

        @Override
        void destroy() {
        }
      }
  }
}
