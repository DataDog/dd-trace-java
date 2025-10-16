package datadog.trace.core.tagprocessor

import datadog.trace.common.metrics.MetricKey
import datadog.trace.test.util.DDSpecification

class HttpEndpointTaggingIntegrationTest extends DDSpecification {

  def "should test http.route vs http.endpoint priority logic"() {
    expect: "route eligibility determines priority"
    HttpEndpointTagging.isRouteEligible("/api/users/{id}")
    !HttpEndpointTagging.isRouteEligible("*")
    !HttpEndpointTagging.isRouteEligible("/*")
    !HttpEndpointTagging.isRouteEligible("/")
  }

  def "should test endpoint computation from URLs for stats bucket enrichment"() {
    expect: "URLs are parameterized for cardinality control"
    HttpEndpointTagging.computeEndpointFromUrl("http://api.com/users/123") == "/users/{param:int}"
    HttpEndpointTagging.computeEndpointFromUrl("http://api.com/sessions/abc123def") == "/sessions/{param:hex}"
    HttpEndpointTagging.computeEndpointFromUrl("http://api.com/tokens/550e8400-e29b-41d4-a716-446655440000") == "/tokens/{param:hex_id}"
    HttpEndpointTagging.computeEndpointFromUrl("http://api.com/files/very-long-filename-that-exceeds-twenty-characters.pdf") == "/files/{param:str}"
  }

  def "should test aggregation key enhancement with HTTP tags"() {
    setup:
    def key1 = new MetricKey(
      "GET /users/{param:int}",
      "web-service",
      "servlet.request",
      "web",
      200,
      false,
      true,
      "server",
      [],
      "GET",
      "/users/{param:int}"
      )

    def key2 = new MetricKey(
      "GET /users/{param:int}",
      "web-service",
      "servlet.request",
      "web",
      200,
      false,
      true,
      "server",
      [],
      "GET",
      "/users/{param:int}"
      )

    def key3 = new MetricKey(
      "GET /users/{param:int}",
      "web-service",
      "servlet.request",
      "web",
      200,
      false,
      true,
      "server",
      [],
      "POST",  // Different method
      "/users/{param:int}"
      )

    expect: "HTTP method and endpoint are part of aggregation key"
    key1.equals(key2)
    !key1.equals(key3)  // Different HTTP method should create different key
    key1.hashCode() == key2.hashCode()
    key1.hashCode() != key3.hashCode()
  }

  def "should test backend reliability scenario"() {
    expect: "eligible routes should be preferred over URL parameterization"
    HttpEndpointTagging.isRouteEligible("/api/v1/users/{userId}/orders/{orderId}")
    HttpEndpointTagging.isRouteEligible("/health/check")
    HttpEndpointTagging.isRouteEligible("/api/*")  // This is eligible because it contains non-wildcard content
    !HttpEndpointTagging.isRouteEligible("*")
    !HttpEndpointTagging.isRouteEligible("/*")     // This should be ineligible
    !HttpEndpointTagging.isRouteEligible("/")      // This should be ineligible
  }

  def "should provide cardinality control for service entry spans"() {
    expect: "service entry spans get parameterized endpoints for identification"
    HttpEndpointTagging.computeEndpointFromUrl("http://api.service.com/health/check") == "/health/check"
    HttpEndpointTagging.computeEndpointFromUrl("http://api.service.com/metrics") == "/metrics"
    HttpEndpointTagging.computeEndpointFromUrl("http://api.service.com/api/users/123/profile") == "/api/users/{param:int}/profile"
  }
}
