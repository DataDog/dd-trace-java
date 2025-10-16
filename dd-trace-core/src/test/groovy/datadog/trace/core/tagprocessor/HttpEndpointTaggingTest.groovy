package datadog.trace.core.tagprocessor

import datadog.trace.test.util.DDSpecification

class HttpEndpointTaggingTest extends DDSpecification {

  def "should accept valid routes"() {
    expect:
    HttpEndpointTagging.isRouteEligible("/api/users/{id}")
    HttpEndpointTagging.isRouteEligible("/v1/orders/{orderId}/items")
    HttpEndpointTagging.isRouteEligible("/users")
    HttpEndpointTagging.isRouteEligible("/health")
    HttpEndpointTagging.isRouteEligible("/api/v2/products/{productId}")
  }

  def "should reject invalid routes"() {
    expect:
    !HttpEndpointTagging.isRouteEligible(null)
    !HttpEndpointTagging.isRouteEligible("")
    !HttpEndpointTagging.isRouteEligible("   ")
    !HttpEndpointTagging.isRouteEligible("not-a-path")
    !HttpEndpointTagging.isRouteEligible("ftp://example.com")
    !HttpEndpointTagging.isRouteEligible("relative/path")
  }

  def "should parameterize UUID segments with hex_id token"() {
    expect:
    HttpEndpointTagging.parameterizeUrlPath("/api/users/123e4567-e89b-12d3-a456-426614174000") == "/api/users/{param:hex_id}"
    HttpEndpointTagging.parameterizeUrlPath("/api/users/550e8400-e29b-41d4-a716-446655440000/orders") == "/api/users/{param:hex_id}/orders"
    HttpEndpointTagging.parameterizeUrlPath("/api/users/f47ac10b-58cc-4372-a567-0e02b2c3d479/profile") == "/api/users/{param:hex_id}/profile"
  }

  def "should parameterize numeric segments with int token"() {
    expect:
    HttpEndpointTagging.parameterizeUrlPath("/api/users/12345") == "/api/users/{param:int}"
    HttpEndpointTagging.parameterizeUrlPath("/api/orders/67890/items/123") == "/api/orders/{param:int}/items/{param:int}"
    HttpEndpointTagging.parameterizeUrlPath("/api/products/999") == "/api/products/{param:int}"
  }

  def "should parameterize hex segments with hex token"() {
    expect:
    HttpEndpointTagging.parameterizeUrlPath("/api/sessions/abc123def456") == "/api/sessions/{param:hex}"
    HttpEndpointTagging.parameterizeUrlPath("/api/tokens/deadbeef") == "/api/tokens/deadbeef"  // No digits, so no match
    HttpEndpointTagging.parameterizeUrlPath("/api/hashes/1a2b3c4d5e6f") == "/api/hashes/{param:hex}"
  }

  def "should parameterize int_id segments with int_id token"() {
    expect:
    // Pattern: (?=.*[0-9].*)[0-9._-]{3,} → {param:int_id}
    // This pattern matches strings that:
    // 1. Have at least one digit ((?=.*[0-9].*) lookahead)
    // 2. Contain only digits, dots, underscores, or dashes ([0-9._-])
    // 3. Are at least 3 characters long ({3,})

    // Test cases that should match int_id pattern
    HttpEndpointTagging.parameterizeUrlPath("/api/orders/0.99") == "/api/orders/{param:int_id}"  // Starts with 0, has dot, 3+ chars
    HttpEndpointTagging.parameterizeUrlPath("/api/versions/0123") == "/api/versions/{param:int_id}"  // Starts with 0, 3+ chars
    HttpEndpointTagging.parameterizeUrlPath("/api/refs/12.34") == "/api/refs/{param:int_id}"  // Has dot, 3+ chars
    HttpEndpointTagging.parameterizeUrlPath("/api/items/1.2.3") == "/api/items/{param:int_id}"  // Has dots, won't match int pattern first
    HttpEndpointTagging.parameterizeUrlPath("/api/ids/123-456") == "/api/ids/{param:int_id}"  // Has dash, 3+ chars
    HttpEndpointTagging.parameterizeUrlPath("/api/codes/9_8_7") == "/api/codes/{param:int_id}"  // Has underscores, 3+ chars

    // Test cases that should NOT match int_id pattern
    HttpEndpointTagging.parameterizeUrlPath("/api/users/abc") == "/api/users/abc"  // No digits
    HttpEndpointTagging.parameterizeUrlPath("/api/users/10") == "/api/users/{param:int}"   // Matches int pattern first (starts with 1, has digits)
    HttpEndpointTagging.parameterizeUrlPath("/api/mixed/12a") == "/api/mixed/12a"  // Contains letter 'a', not allowed in int_id

    // Test edge cases for int_id pattern
    HttpEndpointTagging.parameterizeUrlPath("/api/test/01") == "/api/test/01"   // Starts with 0, only 2 chars, too short for int_id
    HttpEndpointTagging.parameterizeUrlPath("/api/test/012") == "/api/test/{param:int_id}"   // Starts with 0, 3+ chars, matches int_id
  }


  def "should parameterize hex_id segments with hex_id token"() {
    expect:
    // Pattern: (?=.*[0-9].*)[A-Fa-f0-9._-]{6,} → {param:hex_id}
    // This pattern matches strings that:
    // 1. Have at least one digit ((?=.*[0-9].*) lookahead)
    // 2. Contain only hex digits, dots, underscores, or dashes ([A-Fa-f0-9._-])
    // 3. Are at least 6 characters long ({6,})

    // Test cases that should match hex_id pattern
    HttpEndpointTagging.parameterizeUrlPath("/api/sessions/abc123-def456") == "/api/sessions/{param:hex_id}"  // Has digits, hex chars, delimiters, 6+ chars
    HttpEndpointTagging.parameterizeUrlPath("/api/tokens/deadbeef.123") == "/api/tokens/{param:hex_id}"  // Has digits, hex chars, delimiters, 6+ chars
    HttpEndpointTagging.parameterizeUrlPath("/api/hashes/1a2b3c_4d5e6f") == "/api/hashes/{param:hex_id}"  // Has digits, hex chars, delimiters, 6+ chars
    HttpEndpointTagging.parameterizeUrlPath("/api/uuids/550e8400-e29b-41d4-a716-446655440000") == "/api/uuids/{param:hex_id}"  // UUID format
    HttpEndpointTagging.parameterizeUrlPath("/api/keys/abc123def") == "/api/keys/{param:hex}"  // Matches hex pattern first (pure hex chars), not hex_id

    // Test cases that should NOT match hex_id pattern
    HttpEndpointTagging.parameterizeUrlPath("/api/pure/abcdef") == "/api/pure/abcdef"  // No digits, so no match
    HttpEndpointTagging.parameterizeUrlPath("/api/short/a1b2c") == "/api/short/a1b2c"  // Only 5 chars, less than required 6
    HttpEndpointTagging.parameterizeUrlPath("/api/invalid/123xyz") == "/api/invalid/123xyz"  // Contains 'x', 'y', 'z' which are not in [A-Fa-f0-9._-]

    // Test cases that might match other patterns first
    HttpEndpointTagging.parameterizeUrlPath("/api/numbers/123456") == "/api/numbers/{param:int}"  // Matches int pattern first (starts with 1, all digits)
    HttpEndpointTagging.parameterizeUrlPath("/api/tokens/deadbeef") == "/api/tokens/deadbeef"
    // Should not match if less than 6 characters
    HttpEndpointTagging.parameterizeUrlPath("/api/tokens/abc12") == "/api/tokens/abc12"
  }


  def "should parameterize mixed segments correctly"() {
    expect:
    HttpEndpointTagging.parameterizeUrlPath("/api/users/123/orders/abc456def/items/789") == "/api/users/{param:int}/orders/{param:hex}/items/{param:int}"
    HttpEndpointTagging.parameterizeUrlPath("/api/v1/users/550e8400-e29b-41d4-a716-446655440000/sessions/deadbeef") == "/api/v1/users/{param:hex_id}/sessions/deadbeef"  // deadbeef has no digits
  }

  def "should preserve static segments"() {
    expect:
    HttpEndpointTagging.parameterizeUrlPath("/api/users") == "/api/users"
    HttpEndpointTagging.parameterizeUrlPath("/health") == "/health"
  }

  def "should reject ineligible routes according to specification"() {
    expect:
    !HttpEndpointTagging.isRouteEligible(null)
    !HttpEndpointTagging.isRouteEligible("")
    !HttpEndpointTagging.isRouteEligible("   ")
    !HttpEndpointTagging.isRouteEligible("*")
    !HttpEndpointTagging.isRouteEligible("/*")
    !HttpEndpointTagging.isRouteEligible("/")
    !HttpEndpointTagging.isRouteEligible("**")
    !HttpEndpointTagging.isRouteEligible("*/")
    !HttpEndpointTagging.isRouteEligible("no-leading-slash")

    HttpEndpointTagging.isRouteEligible("/api/users")
    HttpEndpointTagging.isRouteEligible("/health")
    HttpEndpointTagging.isRouteEligible("/api/v1/users/{id}")
  }

  def "should handle edge cases"() {
    expect:
    HttpEndpointTagging.parameterizeUrlPath("/") == "/"
    HttpEndpointTagging.parameterizeUrlPath("") == ""
    HttpEndpointTagging.parameterizeUrlPath(null) == null
    HttpEndpointTagging.parameterizeUrlPath("/api/users/123/") == "/api/users/{param:int}"
  }

  def "should handle query parameters"() {
    expect:
    HttpEndpointTagging.parameterizeUrlPath("/api/users/123?param=value") == "/api/users/{param:int}"
    HttpEndpointTagging.parameterizeUrlPath("/api/search?q=test&limit=10") == "/api/search"
    HttpEndpointTagging.parameterizeUrlPath("/api/users/abc123?filter=active") == "/api/users/{param:hex}"
  }

  def "should handle fragments"() {
    expect:
    HttpEndpointTagging.parameterizeUrlPath("/api/users/123#section") == "/api/users/{param:int}"
    HttpEndpointTagging.parameterizeUrlPath("/api/docs#introduction") == "/api/docs"
  }

  def "should compute endpoint from valid URLs"() {
    expect:
    HttpEndpointTagging.computeEndpointFromUrl("http://example.com/api/users/123") == "/api/users/{param:int}"
    HttpEndpointTagging.computeEndpointFromUrl("https://api.example.com/api/orders/456") == "/api/orders/{param:int}"
    HttpEndpointTagging.computeEndpointFromUrl("http://localhost:8080/health") == "/health"
    HttpEndpointTagging.computeEndpointFromUrl("https://example.com:443/api/v1/users/789?param=value") == "/api/v1/users/{param:int}"
  }

  def "should return default endpoint for invalid URLs"() {
    expect:
    HttpEndpointTagging.computeEndpointFromUrl(null) == "/"
    HttpEndpointTagging.computeEndpointFromUrl("") == "/"
    HttpEndpointTagging.computeEndpointFromUrl("   ") == "/"
    HttpEndpointTagging.computeEndpointFromUrl("not-a-url") == "/"
    HttpEndpointTagging.computeEndpointFromUrl("http://") == "/"
    HttpEndpointTagging.computeEndpointFromUrl("://example.com/path") == "/"
    HttpEndpointTagging.computeEndpointFromUrl("http:///path") == "/"
    HttpEndpointTagging.computeEndpointFromUrl("ftp://example.com/file.txt") == "/file.txt"  // FTP URL matches pattern and extracts path
  }

  def "should return root path for root URLs"() {
    expect:
    HttpEndpointTagging.computeEndpointFromUrl("http://example.com/") == "/"
    HttpEndpointTagging.computeEndpointFromUrl("https://example.com") == "/"
  }

  def "should handle complex paths"() {
    expect:
    HttpEndpointTagging.parameterizeUrlPath("/api/v2/users/123/orders/abc456/items/789/reviews/def123") == "/api/v2/users/{param:int}/orders/{param:hex}/items/{param:int}"  // Limited to 8 elements: api,v2,users,123,orders,abc456,items,789
    HttpEndpointTagging.parameterizeUrlPath("/files/2023/12/document123.pdf") == "/files/{param:int}/{param:int}/document123.pdf"  // document123.pdf is <20 chars and no special chars
    HttpEndpointTagging.parameterizeUrlPath("/api/sessions/550e8400-e29b-41d4-a716-446655440000/refresh") == "/api/sessions/{param:hex_id}/refresh"
  }

  def "should preserve static segments in complex paths"() {
    expect:
    HttpEndpointTagging.parameterizeUrlPath("/api/v1/users/123/profile/settings") == "/api/v1/users/{param:int}/profile/settings"
    HttpEndpointTagging.parameterizeUrlPath("/admin/dashboard/users/456/edit") == "/admin/dashboard/users/{param:int}/edit"
    HttpEndpointTagging.parameterizeUrlPath("/public/assets/images/user-789/avatar.png") == "/public/assets/images/user-789/avatar.png"  // user-789 doesn't match int_id pattern
  }

  def "should limit cardinality through parameterization"() {
    expect:
    HttpEndpointTagging.parameterizeUrlPath("/api/users/1") == "/api/users/1"  // Single digit not matched by [1-9][0-9]+
    HttpEndpointTagging.parameterizeUrlPath("/api/users/99") == "/api/users/{param:int}"
    HttpEndpointTagging.parameterizeUrlPath("/api/users/123456789") == "/api/users/{param:int}"

    // Test that hex strings with digits are parameterized to limit cardinality
    HttpEndpointTagging.parameterizeUrlPath("/api/tokens/abcdef") == "/api/tokens/abcdef"  // No digits, no match
    HttpEndpointTagging.parameterizeUrlPath("/api/tokens/123abc") == "/api/tokens/{param:hex}"
    HttpEndpointTagging.parameterizeUrlPath("/api/tokens/deadbeef123") == "/api/tokens/{param:hex}"
  }

  def "should handle empty path elements correctly"() {
    expect:
    // Test paths with double slashes (empty elements)
    HttpEndpointTagging.parameterizeUrlPath("/api//users/123") == "/api/users/{param:int}"  // Empty element discarded
    HttpEndpointTagging.parameterizeUrlPath("//api/users/123//") == "/api/users/{param:int}"  // Multiple empty elements discarded
    HttpEndpointTagging.parameterizeUrlPath("/api/v1//users//123//orders//456") == "/api/v1/users/{param:int}/orders/{param:int}"  // All empty elements discarded

    // Test 8-element limit with empty elements mixed in
    HttpEndpointTagging.parameterizeUrlPath("/a//b//c//d//e//f//g//h//i//j") == "/a/b/c/d/e/f/g/h"  // Only first 8 non-empty elements kept
  }
}
