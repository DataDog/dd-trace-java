package datadog.trace.core.tagprocessor

import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpanContext
import datadog.trace.core.endpoint.EndpointResolver
import spock.lang.Specification

class HttpEndpointPostProcessorTest extends Specification {

  def "should update resource name with endpoint when enabled"() {
    given:
    def endpointResolver = new EndpointResolver(true, false)
    def processor = new HttpEndpointPostProcessor(endpointResolver)
    def mockContext = Mock(DDSpanContext)
    def tags = [
      (Tags.HTTP_METHOD): "GET",
      (Tags.HTTP_ROUTE): "/greeting",
      (Tags.HTTP_URL): "http://localhost:8080/greeting"
    ]

    when:
    processor.processTags(tags, mockContext, [])

    then:
    1 * mockContext.setResourceName({ it.toString() == "GET /greeting" }, ResourceNamePriorities.HTTP_SERVER_RESOURCE_RENAMING)
  }

  def "should compute simplified endpoint from URL when route is invalid"() {
    given:
    def endpointResolver = new EndpointResolver(true, false)
    def processor = new HttpEndpointPostProcessor(endpointResolver)
    def mockContext = Mock(DDSpanContext)
    def tags = [
      (Tags.HTTP_METHOD): "GET",
      (Tags.HTTP_ROUTE): "*",  // Invalid route
      (Tags.HTTP_URL): "http://localhost:8080/users/123/orders/456"
    ]

    when:
    processor.processTags(tags, mockContext, [])

    then:
    1 * mockContext.setResourceName({ it.toString() == "GET /users/{param:int}/orders/{param:int}" }, ResourceNamePriorities.HTTP_SERVER_RESOURCE_RENAMING)
  }

  def "should skip non-HTTP spans"() {
    given:
    def endpointResolver = new EndpointResolver(true, false)
    def processor = new HttpEndpointPostProcessor(endpointResolver)
    def mockContext = Mock(DDSpanContext)
    def tags = [
      "db.statement": "SELECT * FROM users"
    ]

    when:
    processor.processTags(tags, mockContext, [])

    then:
    0 * mockContext.setResourceName(_, _)
  }

  def "should not process when resource renaming is disabled"() {
    given:
    def endpointResolver = new EndpointResolver(false, false)
    def processor = new HttpEndpointPostProcessor(endpointResolver)
    def mockContext = Mock(DDSpanContext)
    def tags = [
      (Tags.HTTP_METHOD): "GET",
      (Tags.HTTP_ROUTE): "/greeting"
    ]

    when:
    processor.processTags(tags, mockContext, [])

    then:
    0 * mockContext.setResourceName(_, _)
  }

  def "should use simplified endpoint when alwaysSimplified is true"() {
    given:
    def endpointResolver = new EndpointResolver(true, true)
    def processor = new HttpEndpointPostProcessor(endpointResolver)
    def mockContext = Mock(DDSpanContext)
    def tags = [
      (Tags.HTTP_METHOD): "GET",
      (Tags.HTTP_ROUTE): "/greeting",
      (Tags.HTTP_URL): "http://localhost:8080/users/123"
    ]

    when:
    processor.processTags(tags, mockContext, [])

    then:
    1 * mockContext.setResourceName({ it.toString() == "GET /users/{param:int}" }, ResourceNamePriorities.HTTP_SERVER_RESOURCE_RENAMING)
  }
}
