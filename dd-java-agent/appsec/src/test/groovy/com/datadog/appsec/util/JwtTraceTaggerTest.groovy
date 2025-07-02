package com.datadog.appsec.util

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import spock.lang.Specification

class JwtTraceTaggerTest extends Specification {

  def "should tag JWT claims from derivatives"() {
    given:
    def mockSpan = Mock(AgentSpan)
    AgentTracer.metaClass.static.activeSpan = { -> mockSpan }

    def jwtJson = '''
        {
            "header": {
                "alg": "HS256",
                "typ": "JWT"
            },
            "payload": {
                "sub": "user123",
                "exp": 1640995200,
                "iat": 1640908800,
                "iss": "example.com"
            },
            "signature": {
                "available": true
            }
        }
        '''

    def derivatives = ["server.request.jwt": jwtJson]

    when:
    JwtTraceTagger.tagJwtClaimsFromDerivatives(derivatives)

    then:
    1 * mockSpan.setTag("jwt.header.alg", "HS256")
    1 * mockSpan.setTag("jwt.header.typ", "JWT")
    1 * mockSpan.setTag("jwt.payload.sub", "user123")
    1 * mockSpan.setTag("jwt.payload.exp", "1640995200")
    1 * mockSpan.setTag("jwt.signature.available", "true")
    0 * mockSpan.setTag(_, _)
  }

  def "should handle missing JWT in derivatives"() {
    given:
    def mockSpan = Mock(AgentSpan)

    AgentTracer.metaClass.static.activeSpan = { -> mockSpan }

    def derivatives = [:]

    when:
    JwtTraceTagger.tagJwtClaimsFromDerivatives(derivatives)

    then:
    0 * mockSpan.setTag(_, _)
  }

  def "should handle invalid JSON"() {
    given:
    def mockSpan = Mock(AgentSpan)

    AgentTracer.metaClass.static.activeSpan = { -> mockSpan }

    def derivatives = ["server.request.jwt": "invalid json"]

    when:
    JwtTraceTagger.tagJwtClaimsFromDerivatives(derivatives)

    then:
    0 * mockSpan.setTag(_, _)
  }

  def "should handle missing fields in JWT"() {
    given:
    def mockSpan = Mock(AgentSpan)

    AgentTracer.metaClass.static.activeSpan = { -> mockSpan }

    def jwtJson = '''
        {
            "header": {
                "alg": "HS256"
            },
            "payload": {
                "sub": "user123"
            }
        }
        '''

    def derivatives = ["server.request.jwt": jwtJson]

    when:
    JwtTraceTagger.tagJwtClaimsFromDerivatives(derivatives)

    then:
    1 * mockSpan.setTag("jwt.header.alg", "HS256")
    1 * mockSpan.setTag("jwt.payload.sub", "user123")
    0 * mockSpan.setTag("jwt.header.typ", _)
    0 * mockSpan.setTag("jwt.payload.exp", _)
    0 * mockSpan.setTag("jwt.signature.available", _)
  }

  def "should handle null span"() {
    given:
    AgentTracer.metaClass.static.activeSpan = { -> null }

    def jwtJson = '''
        {
            "header": {
                "alg": "HS256",
                "typ": "JWT"
            },
            "payload": {
                "sub": "user123"
            }
        }
        '''

    def derivatives = ["server.request.jwt": jwtJson]

    when:
    JwtTraceTagger.tagJwtClaimsFromDerivatives(derivatives)

    then:
    noExceptionThrown()
  }
}
