package datadog.trace.instrumentation.springweb6.urlhandlermapping

import datadog.trace.agent.test.base.HttpServerTest
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.web.servlet.filter.OrderedRequestContextFilter
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.stereotype.Controller
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.RequestContextFilter
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import org.springframework.web.servlet.mvc.AbstractController

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.forPath

@SpringBootApplication
class UrlHandlerMappingAppConfig implements WebMvcConfigurer {
  @Bean
  SimpleUrlHandlerMapping sampleServletMapping() {

    Map<String, ?> urlProperties = new HashMap<>()
    urlProperties.put('/path/{id:\\d+}/param', 'testPathParamController')
    urlProperties.put('/**', 'testRestController')

    def ret = new SimpleUrlHandlerMapping(urlProperties, Ordered.HIGHEST_PRECEDENCE)
    ret.setPatternParser(null)
    ret
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
      def pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE).toString()
      response.writer << new AntPathMatcher().extractUriTemplateVariables(pattern, request.getRequestURI())['id'].toString()
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

    HttpServerTest.ServerEndpoint endpoint = forPath(request.requestURI)
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
