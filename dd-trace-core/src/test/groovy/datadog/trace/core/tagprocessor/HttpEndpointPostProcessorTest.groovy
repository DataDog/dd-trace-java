package datadog.trace.core.tagprocessor

import datadog.trace.api.TagMap
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.api.WritableSpanLinks
import datadog.trace.core.DDSpanContext
import datadog.trace.api.endpoint.EndpointResolver
import spock.lang.Specification

class HttpEndpointPostProcessorTest extends Specification {

  def "should not overwrite resource name when http.route is available and eligible"() {
    // RFC-1051: the processor enriches stats buckets and tags the span with http.endpoint,
    // but must NOT overwrite the span's resourceName — that is the backend's responsibility.
    given:
    def endpointResolver = new EndpointResolver(true, false)
    def processor = new HttpEndpointPostProcessor(endpointResolver)
    def mockContext = Mock(DDSpanContext)
    def mockSpanLinks = Mock(WritableSpanLinks)
    def tags = TagMap.fromMap([
      (Tags.HTTP_METHOD): "GET",
      (Tags.HTTP_ROUTE): "/greeting",
      (Tags.HTTP_URL): "http://localhost:8080/greeting"
    ])

    when:
    processor.processTags(tags, mockContext, mockSpanLinks)

    then:
    0 * mockContext.setResourceName(_, _)
    // http.route is eligible — no http.endpoint tag should be added
    !tags.containsKey(Tags.HTTP_ENDPOINT)
  }

  def "should compute and tag http.endpoint from URL when route is invalid, without touching resourceName"() {
    given:
    def endpointResolver = new EndpointResolver(true, false)
    def processor = new HttpEndpointPostProcessor(endpointResolver)
    def mockContext = Mock(DDSpanContext)
    def mockSpanLinks = Mock(WritableSpanLinks)
    def tags = TagMap.fromMap([
      (Tags.HTTP_METHOD): "GET",
      (Tags.HTTP_ROUTE): "*",  // catch-all — ineligible per RFC-1051
      (Tags.HTTP_URL): "http://localhost:8080/users/123/orders/456"
    ])

    when:
    processor.processTags(tags, mockContext, mockSpanLinks)

    then:
    0 * mockContext.setResourceName(_, _)
    tags[Tags.HTTP_ENDPOINT] == "/users/{param:int}/orders/{param:int}"
  }

  def "should skip non-HTTP spans"() {
    given:
    def endpointResolver = new EndpointResolver(true, false)
    def processor = new HttpEndpointPostProcessor(endpointResolver)
    def mockContext = Mock(DDSpanContext)
    def mockSpanLinks = Mock(WritableSpanLinks)
    def tags = TagMap.fromMap([
      "db.statement": "SELECT * FROM users"
    ])

    when:
    processor.processTags(tags, mockContext, mockSpanLinks)

    then:
    0 * mockContext.setResourceName(_, _)
    !tags.containsKey(Tags.HTTP_ENDPOINT)
  }

  def "should not process when resource renaming is disabled"() {
    given:
    def endpointResolver = new EndpointResolver(false, false)
    def processor = new HttpEndpointPostProcessor(endpointResolver)
    def mockContext = Mock(DDSpanContext)
    def mockSpanLinks = Mock(WritableSpanLinks)
    def tags = TagMap.fromMap([
      (Tags.HTTP_METHOD): "GET",
      (Tags.HTTP_ROUTE): "/greeting"
    ])

    when:
    processor.processTags(tags, mockContext, mockSpanLinks)

    then:
    0 * mockContext.setResourceName(_, _)
    !tags.containsKey(Tags.HTTP_ENDPOINT)
  }

  def "should tag http.endpoint from URL even when alwaysSimplified is true, without touching resourceName"() {
    given:
    def endpointResolver = new EndpointResolver(true, true)
    def processor = new HttpEndpointPostProcessor(endpointResolver)
    def mockContext = Mock(DDSpanContext)
    def mockSpanLinks = Mock(WritableSpanLinks)
    def tags = TagMap.fromMap([
      (Tags.HTTP_METHOD): "GET",
      (Tags.HTTP_ROUTE): "/greeting",
      (Tags.HTTP_URL): "http://localhost:8080/users/123"
    ])

    when:
    processor.processTags(tags, mockContext, mockSpanLinks)

    then:
    0 * mockContext.setResourceName(_, _)
    tags[Tags.HTTP_ENDPOINT] == "/users/{param:int}"
  }
}
