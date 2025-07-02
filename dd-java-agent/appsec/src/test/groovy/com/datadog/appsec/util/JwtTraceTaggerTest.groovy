package com.datadog.appsec.util

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import spock.lang.Specification

class JwtTraceTaggerTest extends Specification {

  def "should tag JWT claims from derivatives"() {
    given:
    def mockSpan = Mock(AgentSpan)

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

    // Parse the JWT JSON as the production code does
    def moshi = new com.squareup.moshi.Moshi.Builder().build()
    def adapter = moshi.adapter(Map)
    def decodedJwt = adapter.fromJson(jwtJson)

    when:
    JwtTraceTagger.tagJwtClaims(decodedJwt, mockSpan)

    then:
    1 * mockSpan.setTag("jwt.header.alg", "HS256")
    1 * mockSpan.setTag("jwt.header.typ", "JWT")
    1 * mockSpan.setTag("jwt.payload.sub", "user123")
    1 * mockSpan.setTag("jwt.payload.exp", "1640995200")
    1 * mockSpan.setTag("jwt.signature.available", "true")
    0 * mockSpan.setTag(_, _)
  }

  def "debug test - should understand what's happening"() {
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
    println "JWT JSON: ${jwtJson}"
    println "Derivatives: ${derivatives}"
    println "Active span before call: ${AgentTracer.activeSpan()}"

    JwtTraceTagger.tagJwtClaimsFromDerivatives(derivatives)

    println "Active span after call: ${AgentTracer.activeSpan()}"

    then:
    // Just verify the method doesn't throw an exception
    noExceptionThrown()
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

    def jwtJson = '''
        {
            "header": {
                "alg": "HS256"
            },
            "payload": {
                "sub": "user123"
            },
            "signature": {}
        }
        '''

    // Parse the JWT JSON as the production code does
    def moshi = new com.squareup.moshi.Moshi.Builder().build()
    def adapter = moshi.adapter(Map)
    def decodedJwt = adapter.fromJson(jwtJson)

    when:
    JwtTraceTagger.tagJwtClaims(decodedJwt, mockSpan)

    then:
    1 * mockSpan.setTag("jwt.header.alg", "HS256")
    1 * mockSpan.setTag("jwt.payload.sub", "user123")
    0 * mockSpan.setTag(_, _)
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
