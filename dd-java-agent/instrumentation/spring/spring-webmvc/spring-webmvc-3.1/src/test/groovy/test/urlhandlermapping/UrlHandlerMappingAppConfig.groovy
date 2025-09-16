package test.urlhandlermapping

import datadog.trace.agent.test.base.HttpServerTest
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.web.filter.OrderedRequestContextFilter
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.stereotype.Controller
import org.springframework.web.filter.RequestContextFilter
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import org.springframework.web.servlet.mvc.AbstractController

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT

@SpringBootApplication
class UrlHandlerMappingAppConfig extends WebMvcConfigurerAdapter {
  @Bean
  SimpleUrlHandlerMapping sampleServletMapping() {

    Properties urlProperties = new Properties()
    urlProperties.put('/path/{id:\\d+}/param', 'testPathParamController')
    urlProperties.put('/**', 'testRestController')

    new SimpleUrlHandlerMapping(
      order: Ordered.HIGHEST_PRECEDENCE,
      mappings: urlProperties
      )
  }

  @Bean
  RequestContextFilter requestContextFilter() {
    new OrderedRequestContextFilter(order: Ordered.HIGHEST_PRECEDENCE)
  }
}

@Controller
class TestPathParamController extends AbstractController {
  @Override
  protected ModelAndView handleRequestInternal(HttpServletRequest request,
    HttpServletResponse response) throws Exception {
    HttpServerTest.controller(PATH_PARAM) {
      def attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)
      response.writer << attribute['id'].toString()
      null
    }
  }
}

@Controller
class TestRestController extends AbstractController {

  @Override
  protected ModelAndView handleRequestInternal(HttpServletRequest request, HttpServletResponse response) throws Exception {
    if (request.requestURI == '/error') {
      response.status = EXCEPTION.status
      response.writer << EXCEPTION.body
      return null
    }

    HttpServerTest.ServerEndpoint endpoint = HttpServerTest.ServerEndpoint.forPath(request.requestURI)
    HttpServerTest.controller(endpoint) {
      switch (endpoint) {
        case EXCEPTION:
          throw new Exception(EXCEPTION.body)
        case REDIRECT:
          response.sendRedirect(endpoint.body)
          break
        default:
          response.status = endpoint.status
          response.writer << endpoint.body
          break
      }
    }
    null
  }
}
