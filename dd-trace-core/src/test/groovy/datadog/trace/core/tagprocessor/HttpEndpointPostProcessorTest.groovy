package datadog.trace.core.tagprocessor

import datadog.trace.api.Config
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpanContext
import datadog.trace.test.util.DDSpecification

class HttpEndpointPostProcessorTest extends DDSpecification {

  def "should not set http.endpoint when resource renaming is disabled"() {
    setup:
    def config = Mock(Config)
    config.isResourceRenamingEnabled() >> false

    def processor = new HttpEndpointPostProcessor(config)
    def spanContext = Mock(DDSpanContext)

    when:
    def tags = [(Tags.HTTP_URL): "http://example.com/users/123"]
    def enrichedTags = processor.processTags(tags, spanContext, [])

    then:
    enrichedTags[Tags.HTTP_ENDPOINT] == null
  }

  def "should set http.endpoint from URL when route is missing"() {
    setup:
    def config = Mock(Config)
    config.isResourceRenamingEnabled() >> true
    config.isResourceRenamingAlwaysSimplifiedEndpoint() >> false

    def processor = new HttpEndpointPostProcessor(config)
    def spanContext = Mock(DDSpanContext)

    when:
    def tags = [(Tags.HTTP_URL): "http://example.com/users/123"]
    def enrichedTags = processor.processTags(tags, spanContext, [])

    then:
    enrichedTags[Tags.HTTP_ENDPOINT] == "/users/{param:int}"
  }

  def "should not set http.endpoint when http.route is eligible"() {
    setup:
    def config = Mock(Config)
    config.isResourceRenamingEnabled() >> true
    config.isResourceRenamingAlwaysSimplifiedEndpoint() >> false

    def processor = new HttpEndpointPostProcessor(config)
    def spanContext = Mock(DDSpanContext)

    when:
    def tags = [
      (Tags.HTTP_ROUTE): "/api/users/{id}",
      (Tags.HTTP_URL): "http://example.com/api/users/12345"
    ]
    def enrichedTags = processor.processTags(tags, spanContext, [])

    then:
    // When route is eligible, http.endpoint is NOT set
    enrichedTags[Tags.HTTP_ENDPOINT] == null
  }

  def "should set http.endpoint from URL when http.route is not eligible"() {
    setup:
    def config = Mock(Config)
    config.isResourceRenamingEnabled() >> true
    config.isResourceRenamingAlwaysSimplifiedEndpoint() >> false

    def processor = new HttpEndpointPostProcessor(config)
    def spanContext = Mock(DDSpanContext)

    when:
    def tags = [
      (Tags.HTTP_ROUTE): ineligibleRoute,
      (Tags.HTTP_URL): "http://example.com/api/users/12345"
    ]
    def enrichedTags = processor.processTags(tags, spanContext, [])

    then:
    enrichedTags[Tags.HTTP_ENDPOINT] == "/api/users/{param:int}"

    where:
    ineligibleRoute | _
    "/"             | _
    "/*"            | _
    "*"             | _
  }

  def "should set http.endpoint from URL in always simplified endpoint mode"() {
    setup:
    def config = Mock(Config)
    config.isResourceRenamingEnabled() >> true
    config.isResourceRenamingAlwaysSimplifiedEndpoint() >> true

    def processor = new HttpEndpointPostProcessor(config)
    def spanContext = Mock(DDSpanContext)

    when:
    def tags = [
      (Tags.HTTP_ROUTE): "/api/users/{id}",
      (Tags.HTTP_URL): "http://example.com/api/users/12345"
    ]
    def enrichedTags = processor.processTags(tags, spanContext, [])

    then:
    // In always simplified mode, URL parameterization is used instead of route
    enrichedTags[Tags.HTTP_ENDPOINT] == "/api/users/{param:int}"
  }

  def "should handle various URL patterns"() {
    setup:
    def config = Mock(Config)
    config.isResourceRenamingEnabled() >> true
    config.isResourceRenamingAlwaysSimplifiedEndpoint() >> false

    def processor = new HttpEndpointPostProcessor(config)
    def spanContext = Mock(DDSpanContext)

    when:
    def enrichedTags = processor.processTags([(Tags.HTTP_URL): url], spanContext, [])

    then:
    enrichedTags[Tags.HTTP_ENDPOINT] == expectedEndpoint

    where:
    url                                                    | expectedEndpoint
    "http://example.com/"                                  | "/"
    "http://example.com/api/users/123"                     | "/api/users/{param:int}"
    "http://example.com/api/resource/abc123def456"         | "/api/resource/{param:hex}"
    "http://example.com/api/resource/abc-123-def"          | "/api/resource/{param:hex_id}"
    "http://example.com/api/resource/123.456.789"          | "/api/resource/{param:int_id}"
    "http://example.com/api/very-long-string-over-twenty-chars" | "/api/{param:str}"
  }

  def "should not set http.endpoint when URL is missing"() {
    setup:
    def config = Mock(Config)
    config.isResourceRenamingEnabled() >> true

    def processor = new HttpEndpointPostProcessor(config)
    def spanContext = Mock(DDSpanContext)

    when:
    def enrichedTags = processor.processTags([:], spanContext, [])

    then:
    // When URL is missing, http.endpoint is not set
    enrichedTags[Tags.HTTP_ENDPOINT] == null
  }

  def "should use default constructor"() {
    when:
    def processor = new HttpEndpointPostProcessor()

    then:
    processor != null
  }
}
