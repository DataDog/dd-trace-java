package datadog.trace.core.endpoint

import spock.lang.Specification

class EndpointResolverTest extends Specification {

  def "isRouteEligible identifies valid routes"() {
    expect:
    EndpointResolver.isRouteEligible(input) == expected

    where:
    input           | expected
    // Valid routes
    "/users"        | true
    "/api/v1/users" | true
    "/orders/123"   | true
    "/.*/"          | true   // Regex fallback is valid
    "/users/*"      | true   // Partial wildcard is valid
    // Invalid routes (instrumentation problems)
    "*"             | false  // Catch-all
    "*/*"           | false  // Double catch-all
    null            | false  // Missing
    ""              | false  // Empty
  }

  def "computeEndpoint delegates to EndpointSimplifier"() {
    expect:
    EndpointResolver.computeEndpoint(input) == expected

    where:
    input                               | expected
    "/users/123/orders/456"             | "/users/{param:int}/orders/{param:int}"
    "http://example.com/users/123"      | "/users/{param:int}"
    null                                | null
    ""                                  | null
  }

  def "disabled resolver returns null"() {
    given:
    def resolver = new EndpointResolver(false, false)
    def unsafeTags = [:]

    when:
    def result = resolver.resolveEndpoint(unsafeTags, "/users", "http://example.com/users/123")

    then:
    result == null
    unsafeTags.isEmpty()  // No tagging
  }

  def "alwaysSimplifiedEndpoint computes from URL even with valid route"() {
    given:
    def resolver = new EndpointResolver(true, true)
    def unsafeTags = [:]

    when:
    def result = resolver.resolveEndpoint(unsafeTags, "/users", "http://example.com/users/123")

    then:
    result == "/users/{param:int}"
    unsafeTags["http.endpoint"] == "/users/{param:int}"
  }

  def "uses eligible route without tagging"() {
    given:
    def resolver = new EndpointResolver(true, false)
    def unsafeTags = [:]

    when:
    def result = resolver.resolveEndpoint(unsafeTags, "/api/v1/users", "http://example.com/users/123")

    then:
    result == "/api/v1/users"
    unsafeTags.isEmpty()  // No tagging when using route
  }

  def "computes endpoint and tags when route is ineligible"() {
    given:
    def resolver = new EndpointResolver(true, false)
    def unsafeTags = [:]

    when:
    def result = resolver.resolveEndpoint(unsafeTags, ineligibleRoute, url)

    then:
    result == expectedEndpoint
    unsafeTags["http.endpoint"] == expectedEndpoint

    where:
    ineligibleRoute | url                             | expectedEndpoint
    "*"             | "http://example.com/users/123"  | "/users/{param:int}"
    "*/*"           | "http://example.com/orders/456" | "/orders/{param:int}"
    null            | "http://example.com/api/v1"     | "/api/v1"
    ""              | "http://example.com/search"     | "/search"
  }

  def "isEnabled returns configuration value"() {
    expect:
    new EndpointResolver(true, false).isEnabled() == true
    new EndpointResolver(false, false).isEnabled() == false
  }

  def "isAlwaysSimplifiedEndpoint returns configuration value"() {
    expect:
    new EndpointResolver(true, true).isAlwaysSimplifiedEndpoint() == true
    new EndpointResolver(true, false).isAlwaysSimplifiedEndpoint() == false
  }

  def "real-world scenario: service with route"() {
    given:
    def resolver = new EndpointResolver(true, false)
    def unsafeTags = [:]

    when: "framework provides accurate route"
    def result = resolver.resolveEndpoint(unsafeTags, "/api/v2/customers/{id}/orders", "http://api.example.com/api/v2/customers/123/orders")

    then:
    result == "/api/v2/customers/{id}/orders"
    unsafeTags.isEmpty()  // Uses route, no endpoint tag
  }

  def "real-world scenario: proxy without route"() {
    given:
    def resolver = new EndpointResolver(true, false)
    def unsafeTags = [:]

    when: "no route available (tracer in proxy)"
    def result = resolver.resolveEndpoint(unsafeTags, null, "http://api.example.com/api/v2/customers/123/orders")

    then:
    result == "/api/v2/customers/{param:int}/orders"
    unsafeTags["http.endpoint"] == "/api/v2/customers/{param:int}/orders"
  }

  def "real-world scenario: bad instrumentation with catch-all"() {
    given:
    def resolver = new EndpointResolver(true, false)
    def unsafeTags = [:]

    when: "instrumentation provides unhelpful catch-all route"
    def result = resolver.resolveEndpoint(unsafeTags, "*/*", "http://api.example.com/users/abc-123/profile")

    then:
    result == "/users/{param:hex_id}/profile"
    unsafeTags["http.endpoint"] == "/users/{param:hex_id}/profile"
  }

  def "real-world scenario: testing with always simplified"() {
    given:
    def resolver = new EndpointResolver(true, true)
    def unsafeTags = [:]

    when: "testing mode to compare with backend inference"
    def result = resolver.resolveEndpoint(unsafeTags, "/api/v1/users/{userId}", "http://api.example.com/api/v1/users/123")

    then:
    result == "/api/v1/users/{param:int}"
    unsafeTags["http.endpoint"] == "/api/v1/users/{param:int}"
  }

  def "edge case: malformed URL returns default endpoint"() {
    given:
    def resolver = new EndpointResolver(true, false)
    def unsafeTags = [:]

    when:
    def result = resolver.resolveEndpoint(unsafeTags, null, "not-a-url")

    then:
    // EndpointSimplifier returns "/" for malformed URLs
    result == "/"
    unsafeTags["http.endpoint"] == "/"
  }

  def "edge case: both route and URL missing"() {
    given:
    def resolver = new EndpointResolver(true, false)
    def unsafeTags = [:]

    when:
    def result = resolver.resolveEndpoint(unsafeTags, null, null)

    then:
    result == null
    unsafeTags.isEmpty()
  }
}
