package com.datadog.appsec.util

import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.DataBundle
import spock.lang.Specification

/**
 * Test to verify JWT preprocessor compliance with the technical specification.
 * Tests the exact schema and example provided in the specification.
 */
class JwtSpecificationComplianceTest extends Specification {

  void 'test JWT structure matches technical specification exactly'() {
    given:
    // Example JWT from the technical specification
    def jwtToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    when:
    DataBundle result = JwtPreprocessor.processJwt(jwtToken)

    then:
    result != null
    result.hasAddress(KnownAddresses.REQUEST_JWT)
    result.size() == 1

    def jwt = result.get(KnownAddresses.REQUEST_JWT) as Map<String, Object>
    jwt != null

    // Verify the structure matches the technical specification schema
    jwt.size() == 3
    jwt.containsKey("header")
    jwt.containsKey("payload")
    jwt.containsKey("signature")

    // Verify header structure matches spec
    def header = jwt.get("header") as Map<String, Object>
    header.size() == 2
    header.get("alg") == "HS256"
    header.get("typ") == "JWT"

    // Verify payload structure matches spec - preserve original JSON types
    def payload = jwt.get("payload") as Map<String, Object>
    payload.size() == 3
    payload.get("sub") == "1234567890"
    payload.get("name") == "John Doe"
    payload.get("iat") == 1516239022L

    // Verify signature structure matches spec
    def signature = jwt.get("signature") as Map<String, Object>
    signature.size() == 1
    signature.containsKey("available")
    signature.get("available") == true
  }

  void 'test JWT address matches technical specification'() {
    expect:
    KnownAddresses.REQUEST_JWT.getKey() == "server.request.jwt"
  }

  void 'test JWT structure schema matches specification requirements'() {
    given:
    def jwtToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    when:
    DataBundle result = JwtPreprocessor.processJwt(jwtToken)

    then:
    result != null
    def jwt = result.get(KnownAddresses.REQUEST_JWT) as Map<String, Object>

    // Verify the schema matches the specification:
    // {
    //   "header": { ... },
    //   "payload": { ... },
    //   "signature": {
    //     "available": true|false
    //   }
    // }

    jwt instanceof Map
    jwt.get("header") instanceof Map
    jwt.get("payload") instanceof Map
    jwt.get("signature") instanceof Map

    def signature = jwt.get("signature") as Map<String, Object>
    signature.get("available") instanceof Boolean
  }

  void 'test JWT with no signature (unsecured JWT) matches specification'() {
    given:
    // Unsecured JWT with alg: "none" - valid according to RFC
    def jwtToken = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJzdWIiOiIxMjM0NTY3ODkwIn0"

    when:
    DataBundle result = JwtPreprocessor.processJwt(jwtToken)

    then:
    result != null
    def jwt = result.get(KnownAddresses.REQUEST_JWT) as Map<String, Object>

    def header = jwt.get("header") as Map<String, Object>
    header.get("alg") == "none"
    header.get("typ") == "JWT"

    def payload = jwt.get("payload") as Map<String, Object>
    payload.get("sub") == "1234567890"

    def signature = jwt.get("signature") as Map<String, Object>
    signature.get("available") == false
  }

  void 'test JWT structure allows access to components via key path'() {
    given:
    def jwtToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    when:
    DataBundle result = JwtPreprocessor.processJwt(jwtToken)

    then:
    result != null
    def jwt = result.get(KnownAddresses.REQUEST_JWT) as Map<String, Object>

    // Verify that components can be accessed via key path as specified:
    // server.request.jwt::header.alg
    // server.request.jwt::payload.exp
    def header = jwt.get("header") as Map<String, Object>
    def payload = jwt.get("payload") as Map<String, Object>
    def signature = jwt.get("signature") as Map<String, Object>

    // Test key path access simulation - preserve original JSON types
    header.get("alg") == "HS256"
    header.get("typ") == "JWT"
    payload.get("sub") == "1234567890"
    payload.get("name") == "John Doe"
    payload.get("iat") == 1516239022L
    signature.get("available") == true
  }

  void 'test JWT structure preserves all original claims'() {
    given:
    // JWT with various standard and custom claims
    def jwtToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyLCJleHAiOjE1MTYyMzkwMjMsImlzcyI6Imh0dHA6Ly9leGFtcGxlLmNvbSIsImF1ZCI6WyJhcHAxIiwiYXBwMiJdLCJqdGkiOiJhYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5eiIsIm5iZiI6MTUxNjIzOTAyMSwiY3VzdG9tX2NsYWltIjoiY3VzdG9tX3ZhbHVlIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    when:
    DataBundle result = JwtPreprocessor.processJwt(jwtToken)

    then:
    result != null
    def jwt = result.get(KnownAddresses.REQUEST_JWT) as Map<String, Object>
    def payload = jwt.get("payload") as Map<String, Object>

    // Verify all standard claims are preserved with original JSON types
    payload.get("sub") == "1234567890"
    payload.get("name") == "John Doe"
    payload.get("iat") == 1516239022L
    payload.get("exp") == 1516239023L
    payload.get("iss") == "http://example.com"
    payload.get("aud") == ["app1", "app2"]
    payload.get("jti") == "abcdefghijklmnopqrstuvwxyz"
    payload.get("nbf") == 1516239021L

    // Verify custom claims are preserved
    payload.get("custom_claim") == "custom_value"
  }

  void 'test JWT structure handles numeric claims correctly'() {
    given:
    // JWT with various numeric claim formats
    def jwtToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwiaWF0IjoxNTE2MjM5MDIyLCJleHAiOjE1MTYyMzkwMjMsIm5iZiI6MTUxNjIzOTAyMSwiZXhwaXJhdGlvbl9zdHJpbmciOiIxNTE2MjM5MDIzIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    when:
    DataBundle result = JwtPreprocessor.processJwt(jwtToken)

    then:
    result != null
    def jwt = result.get(KnownAddresses.REQUEST_JWT) as Map<String, Object>
    def payload = jwt.get("payload") as Map<String, Object>

    // Verify numeric claims preserve original JSON types
    payload.get("iat") == 1516239022L
    payload.get("iat") instanceof Long
    payload.get("exp") == 1516239023L
    payload.get("exp") instanceof Long
    payload.get("nbf") == 1516239021L
    payload.get("nbf") instanceof Long

    // Verify string values remain strings (no conversion)
    payload.get("expiration_string") == "1516239023"
    payload.get("expiration_string") instanceof String
  }

  void 'test JWT structure handles complex nested objects'() {
    given:
    // JWT with complex nested payload - fixed to have valid JSON
    def jwtToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibWV0YWRhdGEiOnsicm9sZSI6ImFkbWluIiwicGVybWlzc2lvbnMiOlsicmVhZCIsIndyaXRlIl0sInVzZXJfaW5mbyI6eyJpZCI6MTIzNDU2Nzg5MCwibmFtZSI6IkpvaG4gRG9lIiwiZW1haWwiOiJqb2huQGV4YW1wbGUuY29tIiwicHJvZmlsZSI6eyJhdmF0YXIiOiJodHRwczovL2V4YW1wbGUuY29tL2F2YXRhci5qcGciLCJsb2NhdGlvbiI6Ik5ldyBZb3JrIn19fX0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    when:
    DataBundle result = JwtPreprocessor.processJwt(jwtToken)

    then:
    result != null
    def jwt = result.get(KnownAddresses.REQUEST_JWT) as Map<String, Object>
    def payload = jwt.get("payload") as Map<String, Object>

    // Verify nested objects are preserved
    payload.containsKey("metadata")
    payload.containsKey("sub")

    def metadata = payload.get("metadata") as Map<String, Object>
    metadata.get("role") == "admin"
    metadata.get("permissions") == ["read", "write"]
    metadata.containsKey("user_info")

    def userInfo = metadata.get("user_info") as Map<String, Object>
    userInfo.get("id") == 1234567890L
    userInfo.get("name") == "John Doe"
    userInfo.get("email") == "john@example.com"

    def profile = userInfo.get("profile") as Map<String, Object>
    profile.get("avatar") == "https://example.com/avatar.jpg"
    profile.get("location") == "New York"
  }

  void 'test JWT structure handles decimal numeric claims correctly'() {
    given:
    // JWT with decimal numeric claims as strings
    def jwtToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwicHJpY2UiOiIzLjE0IiwicmF0aW5nIjoiNC41IiwicGVyY2VudGFnZSI6IjEwMC4wIiwid2hvbGVfbnVtYmVyIjoiNDIiLCJub25fbnVtYmVyIjoibm90X2FfbnVtYmVyIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    when:
    DataBundle result = JwtPreprocessor.processJwt(jwtToken)

    then:
    result != null
    def jwt = result.get(KnownAddresses.REQUEST_JWT) as Map<String, Object>
    def payload = jwt.get("payload") as Map<String, Object>

    // Verify string values remain strings (no conversion)
    payload.get("price") == "3.14"
    payload.get("price") instanceof String
    payload.get("rating") == "4.5"
    payload.get("rating") instanceof String
    payload.get("percentage") == "100.0"
    payload.get("percentage") instanceof String

    // Verify string values remain strings (no conversion)
    payload.get("whole_number") == "42"
    payload.get("whole_number") instanceof String

    // Verify non-numeric strings remain as strings
    payload.get("non_number") == "not_a_number"
    payload.get("non_number") instanceof String
  }
}
