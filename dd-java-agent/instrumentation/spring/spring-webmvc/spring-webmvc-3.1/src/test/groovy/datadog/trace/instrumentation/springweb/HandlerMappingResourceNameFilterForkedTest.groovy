package datadog.trace.instrumentation.springweb

import datadog.trace.agent.test.InstrumentationSpecification
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.web.AnnotationConfigWebContextLoader
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.web.servlet.config.annotation.EnableWebMvc

import javax.servlet.FilterChain
import javax.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE

@WebAppConfiguration // TODO this doesn't exist in 3.1.0 so will need rework if we want to test that version
// https://mvnrepository.com/artifact/org.springframework/spring-test-mvc?repo=springio-plugins-release
// https://github.com/spring-attic/spring-test-mvc
@ContextConfiguration(classes = TestConfiguration, loader = AnnotationConfigWebContextLoader)
class HandlerMappingResourceNameFilterForkedTest extends InstrumentationSpecification {
  @EnableWebMvc
  @Configuration
  @ComponentScan(basePackages = "datadog.trace.instrumentation.springweb")
  static class TestConfiguration {
  }

  @Autowired
  HandlerMappingResourceNameFilter filter

  def "test filter doesn't make externally visible changes to request object - url: #url"() {
    given:
    def request = new MockHttpServletRequest("GET", url)
    def response = Mock(HttpServletResponse)
    def filterChain = Mock(FilterChain)

    when:
    runUnderTrace("test-servlet", {
      request.setAttribute(DD_SPAN_ATTRIBUTE, activeSpan())
      filter.doFilterInternal(request, response, filterChain)
    })

    then:
    assert request.getAttributeNames().toList() == [DD_SPAN_ATTRIBUTE]
    0 * response._

    where:
    url << ["/single", "/not-found"]
  }
}
