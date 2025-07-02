package com.datadog.appsec.util

import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.DataBundle
import spock.lang.Specification
import spock.lang.Unroll

class JwtPreprocessorTest extends Specification {

  @Unroll
  def "test JWT processing with #scenario"() {
    when:
    DataBundle result = JwtPreprocessor.processJwt(jwtToken)

    then:
    result != null
    result.hasAddress(KnownAddresses.REQUEST_JWT)

    def jwt = result.get(KnownAddresses.REQUEST_JWT) as Map<String, Object>
    jwt != null
    jwt.containsKey("header")
    jwt.containsKey("payload")
    jwt.containsKey("signature")

    def header = jwt.get("header") as Map<String, Object>
    def payload = jwt.get("payload") as Map<String, Object>
    def signature = jwt.get("signature") as Map<String, Object>

    header != null
    payload != null
    signature != null
    signature.containsKey("available")

    // Verify specific claims if provided
    if (expectedAlg) {
      header.get("alg") == expectedAlg
    }
    if (expectedIss) {
      payload.get("iss") == expectedIss
    }
    if (expectedSub) {
      payload.get("sub") == expectedSub
    }
    if (expectedExp) {
      payload.get("exp") == expectedExp
    }

    where:
    scenario | jwtToken | expectedAlg | expectedIss | expectedSub | expectedExp
    "valid JWT with all claims" | "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyLCJleHAiOjE1MTYyMzkwMjMsImlzcyI6Imh0dHA6Ly9leGFtcGxlLmNvbSJ9.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c" | "HS256" | "http://example.com" | "1234567890" | 1516239023L
    "valid JWT with minimal claims" | "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c" | "HS256" | null | "1234567890" | null
    "valid JWT with custom claims" | "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwicm9sZSI6ImFkbWluIiwiZGVwYXJ0bWVudCI6ImVuZ2luZWVyaW5nIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c" | "HS256" | null | "1234567890" | null
  }

  @Unroll
  def "test invalid JWT handling with #scenario"() {
    when:
    DataBundle result = JwtPreprocessor.processJwt(jwtToken)

    then:
    result == null

    where:
    scenario | jwtToken
    "null token" | null
    "empty string" | ""
    "single part" | "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
    "invalid base64" | "invalid.token.here"
    "malformed JWT" | "not.a.valid.jwt"
    "too many parts" | "part1.part2.part3.part4"
    "non-JWT string" | "this.is.not.a.jwt"
  }

  def "test JWT with numeric expiration"() {
    given:
    def jwtToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwiZXhwIjoxNTE2MjM5MDIzfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    when:
    DataBundle result = JwtPreprocessor.processJwt(jwtToken)

    then:
    result != null
    def jwt = result.get(KnownAddresses.REQUEST_JWT) as Map<String, Object>
    def payload = jwt.get("payload") as Map<String, Object>
    payload.get("exp") == 1516239023L
    payload.get("exp") instanceof Long
  }

  def "test JWT with string expiration"() {
    given:
    def jwtToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwiZXhwIjoiMTUxNjIzOTAyMyJ9.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    when:
    DataBundle result = JwtPreprocessor.processJwt(jwtToken)

    then:
    result != null
    def jwt = result.get(KnownAddresses.REQUEST_JWT) as Map<String, Object>
    def payload = jwt.get("payload") as Map<String, Object>
    payload.get("exp") == 1516239023L
    payload.get("exp") instanceof Long
  }

  def "test JWT structure verification"() {
    given:
    def jwtToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    when:
    DataBundle result = JwtPreprocessor.processJwt(jwtToken)

    then:
    result != null
    result.size() == 1
    result.hasAddress(KnownAddresses.REQUEST_JWT)

    def jwt = result.get(KnownAddresses.REQUEST_JWT) as Map<String, Object>
    jwt.size() == 3
    jwt.containsKey("header")
    jwt.containsKey("payload")
    jwt.containsKey("signature")

    def header = jwt.get("header") as Map<String, Object>
    def payload = jwt.get("payload") as Map<String, Object>
    def signature = jwt.get("signature") as Map<String, Object>

    header.get("alg") == "HS256"
    header.get("typ") == "JWT"

    payload.get("sub") == "1234567890"
    payload.get("name") == "John Doe"
    payload.get("iat") == 1516239022L

    signature.get("available") == true
  }

  def "test JWT with no signature (two parts)"() {
    given:
    // This is a valid unsecured JWT (rare but valid according to RFC)
    def jwtToken = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJzdWIiOiIxMjM0NTY3ODkwIn0"

    when:
    DataBundle result = JwtPreprocessor.processJwt(jwtToken)

    then:
    result != null
    def jwt = result.get(KnownAddresses.REQUEST_JWT) as Map<String, Object>
    jwt.containsKey("header")
    jwt.containsKey("payload")
    jwt.containsKey("signature")

    def header = jwt.get("header") as Map<String, Object>
    header.get("alg") == "none"
    header.get("typ") == "JWT"

    def payload = jwt.get("payload") as Map<String, Object>
    payload.get("sub") == "1234567890"

    def signature = jwt.get("signature") as Map<String, Object>
    signature.get("available") == false
  }

  def "test JWT with complex nested payload"() {
    given:
    def jwtToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibWV0YWRhdGEiOnsicm9sZSI6ImFkbWluIiwicGVybWlzc2lvbnMiOlsicmVhZCIsIndyaXRlIl19fQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    when:
    DataBundle result = JwtPreprocessor.processJwt(jwtToken)

    then:
    result != null
    def jwt = result.get(KnownAddresses.REQUEST_JWT) as Map<String, Object>
    def payload = jwt.get("payload") as Map<String, Object>

    payload.get("sub") == "1234567890"
    payload.containsKey("metadata")

    def metadata = payload.get("metadata") as Map<String, Object>
    metadata.get("role") == "admin"
    metadata.get("permissions") == ["read", "write"]
  }
}