package datadog.trace.core.endpoint

import spock.lang.Specification

class EndpointSimplifierTest extends Specification {

  def "extractPath handles various URL formats"() {
    expect:
    EndpointSimplifier.extractPath(input) == expected

    where:
    input                                      | expected
    // Full URLs
    "http://example.com/users/123"             | "/users/123"
    "https://api.example.com/v1/orders"        | "/v1/orders"
    "http://localhost:8080/path"               | "/path"
    // Path only
    "/users/123"                               | "/users/123"
    "/api/v1/orders"                           | "/api/v1/orders"
    "/"                                        | "/"
    // With query strings
    "/users/123?foo=bar"                       | "/users/123"
    "http://example.com/path?query=value&x=y"  | "/path"
    "/search?q=test"                           | "/search"
    // Edge cases
    "/path?query"                              | "/path"
    "/path?"                                   | "/path"
    "http://example.com/"                      | "/"
    // Invalid/malformed - should return null or handle gracefully
    ""                                         | null
    "not-a-url"                                | null
    "http://"                                  | null
  }

  def "splitAndLimitSegments splits and limits correctly"() {
    expect:
    EndpointSimplifier.splitAndLimitSegments(input, limit) == expected

    where:
    input                           | limit | expected
    "/users/123/orders/456"         | 8     | ["users", "123", "orders", "456"]
    "/a/b/c/d/e/f/g/h/i/j"          | 8     | ["a", "b", "c", "d", "e", "f", "g", "h"]
    "/users//orders///items"        | 8     | ["users", "orders", "items"]  // empty segments removed
    "/"                             | 8     | []
    "/single"                       | 8     | ["single"]
    "/a/b/c"                        | 2     | ["a", "b"]  // limited to 2
    ""                              | 8     | []
  }

  def "simplifySegment delegates to SegmentPattern"() {
    expect:
    EndpointSimplifier.simplifySegment(input) == expected

    where:
    input                                    | expected
    "123"                                    | "{param:int}"
    "1-2-3"                                  | "{param:int_id}"
    "abc123"                                 | "{param:hex}"
    "abc-123"                                | "{param:hex_id}"
    "very-long-string-with-many-characters"  | "{param:str}"
    "users"                                  | "users"
    "api"                                    | "api"
    "user-123"                               | "user-123"  // has letters, not simplified
  }

  def "simplifyPath handles complete paths"() {
    expect:
    EndpointSimplifier.simplifyPath(input) == expected

    where:
    input                                    | expected
    // Simple paths
    "/users"                                 | "/users"
    "/api/v1/users"                          | "/api/v1/users"
    // Paths with IDs
    "/users/123"                             | "/users/{param:int}"
    "/users/123/orders/456"                  | "/users/{param:int}/orders/{param:int}"
    // Mixed parameters
    "/users/abc-123/orders"                  | "/users/{param:hex_id}/orders"
    "/api/v1/users/123/profile"              | "/api/v1/users/{param:int}/profile"
    // Long paths (8 segment limit)
    "/a/b/c/d/e/f/g/h/i/j"                   | "/a/b/c/d/e/f/g/h"
    // Complex real-world examples
    "/orders/abc-123-def/items/456"          | "/orders/{param:hex_id}/items/{param:int}"
    "/v1/users/123/orders/def-456/status"    | "/v1/users/{param:int}/orders/{param:hex_id}/status"
    // Edge cases
    "/"                                      | "/"
    ""                                       | "/"
    null                                     | "/"
    // Empty segments
    "/users//orders"                         | "/users/orders"
    "///users///123///"                      | "/users/{param:int}"
  }

  def "simplifyUrl handles complete URLs"() {
    expect:
    EndpointSimplifier.simplifyUrl(input) == expected

    where:
    input                                                  | expected
    // Full URLs
    "http://example.com/users/123"                         | "/users/{param:int}"
    "https://api.example.com/v1/orders/456/items"          | "/v1/orders/{param:int}/items"
    // URLs with query strings
    "http://example.com/users/123?foo=bar"                 | "/users/{param:int}"
    "https://api.example.com/search?q=test&page=1"         | "/search"
    // Path only
    "/users/123/orders/456"                                | "/users/{param:int}/orders/{param:int}"
    "/api/v1/users/abc123def"                              | "/api/v1/users/{param:hex}"
    // Edge cases
    "/"                                                    | "/"
    ""                                                     | "/"
    null                                                   | "/"
    "http://example.com/"                                  | "/"
    // Complex real-world scenarios
    "https://api.example.com/v2/customers/abc-123-def/orders/999/items/very-long-item-identifier-here" |
      "/v2/customers/{param:hex_id}/orders/{param:int}/items/{param:str}"
  }

  def "cardinality test - ensures bounded output"() {
    given:
    def uniqueEndpoints = [] as Set

    when: "process many URLs with different IDs (starting from 10 to ensure 2+ digits)"
    1000.times { i ->
      def id1 = i + 10  // Ensure at least 2 digits for INTEGER pattern
      def id2 = (i + 10) * 2
      uniqueEndpoints.add(EndpointSimplifier.simplifyUrl("/users/${id1}/orders/${id2}"))
    }

    then: "all map to same endpoint"
    uniqueEndpoints.size() == 1
    uniqueEndpoints.first() == "/users/{param:int}/orders/{param:int}"
  }

  def "cardinality test - different patterns create different endpoints"() {
    given:
    def inputs = [
      "/users/123",
      "/users/abc123",
      "/users/abc-123",
      "/users/very-long-identifier-here",
      "/orders/456",
      "/api/v1/users/123"
    ]

    when:
    def endpoints = inputs.collect { EndpointSimplifier.simplifyUrl(it) } as Set

    then: "different path structures create different endpoints"
    endpoints == [
      "/users/{param:int}",
      "/users/{param:hex}",
      "/users/{param:hex_id}",
      "/users/{param:str}",
      "/orders/{param:int}",
      "/api/v1/users/{param:int}"
    ] as Set
  }

  def "stress test - handles malformed URLs gracefully"() {
    expect:
    EndpointSimplifier.simplifyUrl(input) != null

    where:
    input << [
      "not a url",
      "://broken",
      "http://",
      "?query=only",
      "##fragments",
      "user:pass@host/path",
      "/path with spaces",
      "/path/with/../dots",
      "/path/with/./dots"
    ]
  }

  def "real-world URL examples"() {
    expect:
    EndpointSimplifier.simplifyUrl(input) == expected

    where:
    input                                                          | expected
    // REST APIs
    "https://api.github.com/repos/owner/repo/pulls/123"            | "/repos/owner/repo/pulls/{param:int}"
    "https://api.stripe.com/v1/customers/abc123def/cards"          | "/v1/customers/{param:hex}/cards"
    // E-commerce
    "https://shop.example.com/products/abc-12345/reviews"          | "/products/{param:hex_id}/reviews"
    "https://shop.example.com/cart/abc-123-def/checkout"           | "/cart/{param:hex_id}/checkout"
    // Microservices
    "/api/v2/tenants/123-456/services/abc-def/metrics"             | "/api/v2/tenants/{param:int_id}/services/abc-def/metrics"
    "/internal/health/check/abc-123/status"                        | "/internal/health/check/{param:hex_id}/status"
    // With query parameters (should be stripped)
    "/api/users/123?include=orders&sort=date"                      | "/api/users/{param:int}"
    "/search?q=test&page=1&limit=10"                               | "/search"
  }

  def "special characters in path segments"() {
    expect:
    EndpointSimplifier.simplifyUrl(input) == expected

    where:
    input                                  | expected
    "/users/user@example.com/profile"      | "/users/{param:str}/profile"
    "/search/query%20with%20spaces"        | "/search/{param:str}"
    "/api/v1/items/item+tag"               | "/api/v1/items/{param:str}"
    "/path/segment(with)parens"            | "/path/{param:str}"
  }

  def "preserves static segments"() {
    expect:
    EndpointSimplifier.simplifyUrl(input) == expected

    where:
    input                          | expected
    "/api/v1/users"                | "/api/v1/users"
    "/health/check"                | "/health/check"
    "/metrics/prometheus"          | "/metrics/prometheus"
    "/admin/dashboard"             | "/admin/dashboard"
  }

  def "handles UUID-like patterns"() {
    expect:
    EndpointSimplifier.simplifyUrl(input) == expected

    where:
    input                                               | expected
    "/users/0-1-2-3-4"                                  | "/users/{param:int_id}"
    "/orders/abc-def-123"                               | "/orders/{param:hex_id}"
    "/items/ABC123DEF456"                               | "/items/{param:hex}"
    "/items/abc-123-def"                                | "/items/{param:hex_id}"
  }

  def "segment limit prevents cardinality explosion"() {
    given:
    def deepPath = (1..20).collect { "segment$it" }.join("/")
    def url = "/$deepPath"

    when:
    def simplified = EndpointSimplifier.simplifyUrl(url)

    then: "only first 8 segments are kept"
    simplified == "/segment1/segment2/segment3/segment4/segment5/segment6/segment7/segment8"
  }

  def "handles trailing slashes"() {
    expect:
    EndpointSimplifier.simplifyUrl(input) == expected

    where:
    input                    | expected
    "/users/"                | "/users"
    "/users/123/"            | "/users/{param:int}"
    "/api/v1/orders/"        | "/api/v1/orders"
  }
}
