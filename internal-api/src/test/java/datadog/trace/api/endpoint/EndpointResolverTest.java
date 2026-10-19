package datadog.trace.api.endpoint;

import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ENDPOINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class EndpointResolverTest {

  @TableTest({
    "scenario         | input         | expected",
    // Valid routes
    "simple route     | /users        | true    ",
    "versioned route  | /api/v1/users | true    ",
    "route with id    | /orders/123   | true    ",
    // Regex fallback is valid
    "regex fallback   | '/.*/'        | true    ",
    // Partial wildcard is valid
    "partial wildcard | '/users/*'    | true    ",
    // Invalid routes (instrumentation problems)
    "catch-all        | '*'           | false   ",
    "double catch-all | '*/*'         | false   ",
    "missing          |               | false   ",
    "empty            | ''            | false   "
  })
  void isRouteEligibleIdentifiesValidRoutes(String input, boolean expected) {
    assertEquals(expected, EndpointResolver.isRouteEligible(input));
  }

  @TableTest({
    "scenario    | input                          | expected                               ",
    "path only   | /users/123/orders/456          | '/users/{param:int}/orders/{param:int}'",
    "full URL    | 'http://example.com/users/123' | '/users/{param:int}'                   ",
    "null input  |                                |                                        ",
    "empty input | ''                             |                                        "
  })
  void computeEndpointDelegatesToEndpointSimplifier(String input, String expected) {
    assertEquals(expected, EndpointResolver.computeEndpoint(input));
  }

  @Test
  void disabledResolverReturnsNull() {
    EndpointResolver resolver = new EndpointResolver(false, false);
    Map<String, Object> unsafeTags = new HashMap<>();

    String result = resolver.resolveEndpoint(unsafeTags, "/users", "http://example.com/users/123");

    assertNull(result);
    // No tagging
    assertTrue(unsafeTags.isEmpty());
  }

  @Test
  void alwaysSimplifiedEndpointComputesFromUrlEvenWithValidRoute() {
    EndpointResolver resolver = new EndpointResolver(true, true);
    Map<String, Object> unsafeTags = new HashMap<>();

    String result = resolver.resolveEndpoint(unsafeTags, "/users", "http://example.com/users/123");

    assertEquals("/users/{param:int}", result);
    assertEquals("/users/{param:int}", unsafeTags.get(HTTP_ENDPOINT));
    assertEquals(1, unsafeTags.size());
  }

  @Test
  void usesEligibleRouteWithoutTagging() {
    EndpointResolver resolver = new EndpointResolver(true, false);
    Map<String, Object> unsafeTags = new HashMap<>();

    String result =
        resolver.resolveEndpoint(unsafeTags, "/api/v1/users", "http://example.com/users/123");

    assertEquals("/api/v1/users", result);
    // No tagging when using route
    assertTrue(unsafeTags.isEmpty());
  }

  @TableTest({
    "scenario         | ineligibleRoute | url                             | expectedEndpoint     ",
    "catch-all        | '*'             | 'http://example.com/users/123'  | '/users/{param:int}' ",
    "double catch-all | '*/*'           | 'http://example.com/orders/456' | '/orders/{param:int}'",
    "null route       |                 | 'http://example.com/api/v1'     | /api/v1              ",
    "empty route      | ''              | 'http://example.com/search'     | /search              "
  })
  void computesEndpointAndTagsWhenRouteIsIneligible(
      String ineligibleRoute, String url, String expectedEndpoint) {
    EndpointResolver resolver = new EndpointResolver(true, false);
    Map<String, Object> unsafeTags = new HashMap<>();

    String result = resolver.resolveEndpoint(unsafeTags, ineligibleRoute, url);

    assertEquals(expectedEndpoint, result);
    assertEquals(expectedEndpoint, unsafeTags.get(HTTP_ENDPOINT));
    assertEquals(1, unsafeTags.size());
  }

  @Test
  void isEnabledReturnsConfigurationValue() {
    assertTrue(new EndpointResolver(true, false).isEnabled());
    assertFalse(new EndpointResolver(false, false).isEnabled());
  }

  @Test
  void isAlwaysSimplifiedEndpointReturnsConfigurationValue() {
    assertTrue(new EndpointResolver(true, true).isAlwaysSimplifiedEndpoint());
    assertFalse(new EndpointResolver(true, false).isAlwaysSimplifiedEndpoint());
  }

  @Test
  void realWorldScenarioServiceWithRoute() {
    EndpointResolver resolver = new EndpointResolver(true, false);
    Map<String, Object> unsafeTags = new HashMap<>();

    // framework provides accurate route
    String result =
        resolver.resolveEndpoint(
            unsafeTags,
            "/api/v2/customers/{id}/orders",
            "http://api.example.com/api/v2/customers/123/orders");

    assertEquals("/api/v2/customers/{id}/orders", result);
    // Uses route, no endpoint tag
    assertTrue(unsafeTags.isEmpty());
  }

  @Test
  void realWorldScenarioProxyWithoutRoute() {
    EndpointResolver resolver = new EndpointResolver(true, false);
    Map<String, Object> unsafeTags = new HashMap<>();

    // no route available (tracer in proxy)
    String result =
        resolver.resolveEndpoint(
            unsafeTags, null, "http://api.example.com/api/v2/customers/123/orders");

    assertEquals("/api/v2/customers/{param:int}/orders", result);
    assertEquals("/api/v2/customers/{param:int}/orders", unsafeTags.get(HTTP_ENDPOINT));
    assertEquals(1, unsafeTags.size());
  }

  @Test
  void realWorldScenarioBadInstrumentationWithCatchAll() {
    EndpointResolver resolver = new EndpointResolver(true, false);
    Map<String, Object> unsafeTags = new HashMap<>();

    // instrumentation provides unhelpful catch-all route
    String result =
        resolver.resolveEndpoint(unsafeTags, "*/*", "http://api.example.com/users/abc-123/profile");

    assertEquals("/users/{param:hex_id}/profile", result);
    assertEquals("/users/{param:hex_id}/profile", unsafeTags.get(HTTP_ENDPOINT));
    assertEquals(1, unsafeTags.size());
  }

  @Test
  void realWorldScenarioTestingWithAlwaysSimplified() {
    EndpointResolver resolver = new EndpointResolver(true, true);
    Map<String, Object> unsafeTags = new HashMap<>();

    // testing mode to compare with backend inference
    String result =
        resolver.resolveEndpoint(
            unsafeTags, "/api/v1/users/{userId}", "http://api.example.com/api/v1/users/123");

    assertEquals("/api/v1/users/{param:int}", result);
    assertEquals("/api/v1/users/{param:int}", unsafeTags.get(HTTP_ENDPOINT));
    assertEquals(1, unsafeTags.size());
  }

  @Test
  void edgeCaseMalformedUrlReturnsDefaultEndpoint() {
    EndpointResolver resolver = new EndpointResolver(true, false);
    Map<String, Object> unsafeTags = new HashMap<>();

    String result = resolver.resolveEndpoint(unsafeTags, null, "not-a-url");

    // EndpointSimplifier returns "/" for malformed URLs
    assertEquals("/", result);
    assertEquals("/", unsafeTags.get(HTTP_ENDPOINT));
    assertEquals(1, unsafeTags.size());
  }

  @Test
  void edgeCaseBothRouteAndUrlMissing() {
    EndpointResolver resolver = new EndpointResolver(true, false);
    Map<String, Object> unsafeTags = new HashMap<>();

    String result = resolver.resolveEndpoint(unsafeTags, null, null);

    assertNull(result);
    assertTrue(unsafeTags.isEmpty());
  }
}
