package datadog.trace.api.endpoint;

import static datadog.trace.api.endpoint.EndpointSimplifier.simplifyUrl;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class EndpointSimplifierTest {

  @TableTest({
    "scenario            | input                                     | expected      ",
    // Full URLs
    "http URL            | 'http://example.com/users/123'            | /users/123    ",
    "https URL           | 'https://api.example.com/v1/orders'       | /v1/orders    ",
    "URL with port       | 'http://localhost:8080/path'              | /path         ",
    // Path only
    "path with id        | /users/123                                | /users/123    ",
    "versioned path      | /api/v1/orders                            | /api/v1/orders",
    "root path           | /                                         | /             ",
    // With query strings
    "path with query     | '/users/123?foo=bar'                      | /users/123    ",
    "URL with query      | 'http://example.com/path?query=value&x=y' | /path         ",
    "search with query   | '/search?q=test'                          | /search       ",
    // Edge cases
    "query without value | '/path?query'                             | /path         ",
    "empty query         | '/path?'                                  | /path         ",
    "URL with root path  | 'http://example.com/'                     | /             ",
    // Invalid/malformed - should return null or handle gracefully
    "empty               | ''                                        |               ",
    "not a url           | not-a-url                                 |               ",
    "scheme only         | 'http://'                                 |               "
  })
  void extractPathHandlesVariousUrlFormats(String input, String expected) {
    assertEquals(expected, EndpointSimplifier.extractPath(input));
  }

  @TableTest({
    "scenario       | input                  | limit | expected                 ",
    "four segments  | /users/123/orders/456  | 8     | [users, 123, orders, 456]",
    "over limit     | /a/b/c/d/e/f/g/h/i/j   | 8     | [a, b, c, d, e, f, g, h] ",
    // empty segments removed
    "empty segments | /users//orders///items | 8     | [users, orders, items]   ",
    "root           | /                      | 8     | []                       ",
    "single segment | /single                | 8     | [single]                 ",
    // limited to 2
    "limited to 2   | /a/b/c                 | 2     | [a, b]                   ",
    "empty          | ''                     | 8     | []                       "
  })
  void splitAndLimitSegmentsSplitsAndLimitsCorrectly(
      String input, int limit, List<String> expected) {
    assertEquals(expected, EndpointSimplifier.splitAndLimitSegments(input, limit));
  }

  @TableTest({
    "scenario       | input                                 | expected        ",
    "integer        | 123                                   | '{param:int}'   ",
    "integer id     | 1-2-3                                 | '{param:int_id}'",
    "hex            | abc123                                | '{param:hex}'   ",
    "hex id         | abc-123                               | '{param:hex_id}'",
    "long string    | very-long-string-with-many-characters | '{param:str}'   ",
    "static users   | users                                 | users           ",
    "static api     | api                                   | api             ",
    // has letters, not simplified
    "letters and id | user-123                              | user-123        "
  })
  void simplifySegmentDelegatesToSegmentPattern(String input, String expected) {
    assertEquals(expected, EndpointSimplifier.simplifySegment(input));
  }

  @TableTest({
    "scenario                 | input                               | expected                                            ",
    // Simple paths
    "single segment           | /users                              | /users                                              ",
    "versioned path           | /api/v1/users                       | /api/v1/users                                       ",
    // Paths with IDs
    "one id                   | /users/123                          | '/users/{param:int}'                                ",
    "two ids                  | /users/123/orders/456               | '/users/{param:int}/orders/{param:int}'             ",
    // Mixed parameters
    "hex id                   | /users/abc-123/orders               | '/users/{param:hex_id}/orders'                      ",
    "versioned with id        | /api/v1/users/123/profile           | '/api/v1/users/{param:int}/profile'                 ",
    // Long paths (8 segment limit)
    "long path                | /a/b/c/d/e/f/g/h/i/j                | /a/b/c/d/e/f/g/h                                    ",
    // Complex real-world examples
    "hex id and int           | /orders/abc-123-def/items/456       | '/orders/{param:hex_id}/items/{param:int}'          ",
    "versioned int and hex id | /v1/users/123/orders/def-456/status | '/v1/users/{param:int}/orders/{param:hex_id}/status'",
    // Edge cases
    "root                     | /                                   | /                                                   ",
    "empty                    | ''                                  | /                                                   ",
    "null                     |                                     | /                                                   ",
    // Empty segments
    "empty segment            | /users//orders                      | /users/orders                                       ",
    "many empty segments      | ///users///123///                   | '/users/{param:int}'                                "
  })
  void simplifyPathHandlesCompletePaths(String input, String expected) {
    assertEquals(expected, EndpointSimplifier.simplifyPath(input));
  }

  @TableTest({
    "scenario             | input                                                                                              | expected                                                           ",
    // Full URLs
    "http URL             | 'http://example.com/users/123'                                                                     | '/users/{param:int}'                                               ",
    "https URL            | 'https://api.example.com/v1/orders/456/items'                                                      | '/v1/orders/{param:int}/items'                                     ",
    // URLs with query strings
    "http URL with query  | 'http://example.com/users/123?foo=bar'                                                             | '/users/{param:int}'                                               ",
    "https URL with query | 'https://api.example.com/search?q=test&page=1'                                                     | /search                                                            ",
    // Path only
    "path with ids        | /users/123/orders/456                                                                              | '/users/{param:int}/orders/{param:int}'                            ",
    "path with hex        | /api/v1/users/abc123def                                                                            | '/api/v1/users/{param:hex}'                                        ",
    // Edge cases
    "root                 | /                                                                                                  | /                                                                  ",
    "empty                | ''                                                                                                 | /                                                                  ",
    "null                 |                                                                                                    | /                                                                  ",
    "URL with root path   | 'http://example.com/'                                                                              | /                                                                  ",
    // Complex real-world scenarios
    "complex real-world   | 'https://api.example.com/v2/customers/abc-123-def/orders/999/items/very-long-item-identifier-here' | '/v2/customers/{param:hex_id}/orders/{param:int}/items/{param:str}'"
  })
  void simplifyUrlHandlesCompleteUrls(String input, String expected) {
    assertEquals(expected, simplifyUrl(input));
  }

  @Test
  void cardinalityTestEnsuresBoundedOutput() {
    Set<String> uniqueEndpoints = new HashSet<>();

    // process many URLs with different IDs (starting from 10 to ensure 2+ digits)
    for (int index = 0; index < 1000; index++) {
      // Ensure at least 2 digits for INTEGER pattern
      int id1 = index + 10;
      int id2 = (index + 10) * 2;
      uniqueEndpoints.add(simplifyUrl("/users/" + id1 + "/orders/" + id2));
    }

    // all map to same endpoint
    assertEquals(1, uniqueEndpoints.size());
    assertEquals("/users/{param:int}/orders/{param:int}", uniqueEndpoints.iterator().next());
  }

  @Test
  void cardinalityTestDifferentPatternsCreateDifferentEndpoints() {
    List<String> inputs =
        asList(
            "/users/123",
            "/users/abc123",
            "/users/abc-123",
            "/users/very-long-identifier-here",
            "/orders/456",
            "/api/v1/users/123");

    Set<String> endpoints = new HashSet<>();
    for (String input : inputs) {
      endpoints.add(simplifyUrl(input));
    }

    // different path structures create different endpoints
    Set<String> expected =
        new HashSet<>(
            asList(
                "/users/{param:int}",
                "/users/{param:hex}",
                "/users/{param:hex_id}",
                "/users/{param:str}",
                "/orders/{param:int}",
                "/api/v1/users/{param:int}"));
    assertEquals(expected, endpoints);
  }

  @TableTest({
    "scenario         | input                ",
    "not a url        | 'not a url'          ",
    "missing scheme   | '://broken'          ",
    "scheme only      | 'http://'            ",
    "query only       | '?query=only'        ",
    "fragments        | '##fragments'        ",
    "user info        | 'user:pass@host/path'",
    "path with spaces | '/path with spaces'  ",
    "parent dots      | '/path/with/../dots' ",
    "current dots     | '/path/with/./dots'  "
  })
  void stressTestHandlesMalformedUrlsGracefully(String input) {
    assertNotNull(simplifyUrl(input));
  }

  @TableTest({
    "scenario          | input                                                 | expected                                                 ",
    // REST APIs
    "github pulls      | 'https://api.github.com/repos/owner/repo/pulls/123'   | '/repos/owner/repo/pulls/{param:int}'                    ",
    "stripe cards      | 'https://api.stripe.com/v1/customers/abc123def/cards' | '/v1/customers/{param:hex}/cards'                        ",
    // E-commerce
    "product reviews   | 'https://shop.example.com/products/abc-12345/reviews' | '/products/{param:hex_id}/reviews'                       ",
    "cart checkout     | 'https://shop.example.com/cart/abc-123-def/checkout'  | '/cart/{param:hex_id}/checkout'                          ",
    // Microservices
    "tenant metrics    | /api/v2/tenants/123-456/services/abc-def/metrics      | '/api/v2/tenants/{param:int_id}/services/abc-def/metrics'",
    "health check      | /internal/health/check/abc-123/status                 | '/internal/health/check/{param:hex_id}/status'           ",
    // With query parameters (should be stripped)
    "users with query  | '/api/users/123?include=orders&sort=date'             | '/api/users/{param:int}'                                 ",
    "search with query | '/search?q=test&page=1&limit=10'                      | /search                                                  "
  })
  void realWorldUrlExamples(String input, String expected) {
    assertEquals(expected, simplifyUrl(input));
  }

  @TableTest({
    "scenario       | input                             | expected                    ",
    "email          | '/users/user@example.com/profile' | '/users/{param:str}/profile'",
    "encoded spaces | '/search/query%20with%20spaces'   | '/search/{param:str}'       ",
    "plus sign      | '/api/v1/items/item+tag'          | '/api/v1/items/{param:str}' ",
    "parentheses    | '/path/segment(with)parens'       | '/path/{param:str}'         "
  })
  void specialCharactersInPathSegments(String input, String expected) {
    assertEquals(expected, simplifyUrl(input));
  }

  @TableTest({
    "scenario  | input               | expected           ",
    "api users | /api/v1/users       | /api/v1/users      ",
    "health    | /health/check       | /health/check      ",
    "metrics   | /metrics/prometheus | /metrics/prometheus",
    "admin     | /admin/dashboard    | /admin/dashboard   "
  })
  void preservesStaticSegments(String input, String expected) {
    assertEquals(expected, simplifyUrl(input));
  }

  @TableTest({
    "scenario          | input               | expected                ",
    "integer id        | /users/0-1-2-3-4    | '/users/{param:int_id}' ",
    "hex id            | /orders/abc-def-123 | '/orders/{param:hex_id}'",
    "uppercase hex     | /items/ABC123DEF456 | '/items/{param:hex}'    ",
    "hex id with digit | /items/abc-123-def  | '/items/{param:hex_id}' "
  })
  void handlesUuidLikePatterns(String input, String expected) {
    assertEquals(expected, simplifyUrl(input));
  }

  @Test
  void segmentLimitPreventsCardinalityExplosion() {
    StringBuilder url = new StringBuilder();
    for (int index = 1; index <= 20; index++) {
      url.append("/segment").append(index);
    }

    String simplified = simplifyUrl(url.toString());

    // only first 8 segments are kept
    assertEquals(
        "/segment1/segment2/segment3/segment4/segment5/segment6/segment7/segment8", simplified);
  }

  @TableTest({
    "scenario       | input           | expected            ",
    "static path    | /users/         | /users              ",
    "path with id   | /users/123/     | '/users/{param:int}'",
    "versioned path | /api/v1/orders/ | /api/v1/orders      "
  })
  void handlesTrailingSlashes(String input, String expected) {
    assertEquals(expected, simplifyUrl(input));
  }
}
