package com.datadog.appsec.gateway

import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import datadog.trace.api.gateway.SubscriptionService
import com.datadog.appsec.event.EventProducerService
import com.datadog.appsec.api.security.ApiSecuritySampler
import datadog.trace.test.util.DDSpecification

/**
 * Unit tests for JWT extraction logic in GatewayBridge.
 * Tests private methods through reflection to ensure correct implementation.
 */
class JwtExtractionUnitTest extends DDSpecification {

  def 'test extractJwtToken method handles valid Bearer tokens'() {
    given:
    def bridge = new GatewayBridge(Mock(SubscriptionService), Mock(EventProducerService), { Mock(ApiSecuritySampler) } as java.util.function.Supplier, [])
    def extractMethod = GatewayBridge.getDeclaredMethod('extractJwtToken', String)
    extractMethod.setAccessible(true)

    when:
    def result1 = extractMethod.invoke(bridge, 'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c')
    def result2 = extractMethod.invoke(bridge, 'bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c')
    def result3 = extractMethod.invoke(bridge, 'BEARER eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c')

    then:
    result1 == 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c'
    result2 == 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c'
    result3 == 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c'
  }

  def 'test extractJwtToken method handles invalid or non-Bearer tokens'() {
    given:
    def bridge = new GatewayBridge(Mock(SubscriptionService), Mock(EventProducerService), { Mock(ApiSecuritySampler) } as java.util.function.Supplier, [])
    def extractMethod = GatewayBridge.getDeclaredMethod('extractJwtToken', String)
    extractMethod.setAccessible(true)

    when:
    def result2 = extractMethod.invoke(bridge, '')
    def result3 = extractMethod.invoke(bridge, 'Basic dXNlcjpwYXNz')
    def result4 = extractMethod.invoke(bridge, 'Bearer')
    def result5 = extractMethod.invoke(bridge, 'Bearer ')
    def result6 = extractMethod.invoke(bridge, 'CustomScheme token123')

    then:
    result2 == null
    result3 == null
    result4 == null
    result5 == null
    result6 == null
  }

  def 'test extractJwtToken method handles edge cases'() {
    given:
    def bridge = new GatewayBridge(Mock(SubscriptionService), Mock(EventProducerService), { Mock(ApiSecuritySampler) } as java.util.function.Supplier, [])
    def extractMethod = GatewayBridge.getDeclaredMethod('extractJwtToken', String)
    extractMethod.setAccessible(true)

    when:
    def result1 = extractMethod.invoke(bridge, 'Bearer')
    def result2 = extractMethod.invoke(bridge, 'Bearer ')
    def result3 = extractMethod.invoke(bridge, 'Bearer   ')
    def result4 = extractMethod.invoke(bridge, 'Bearer token')
    def result5 = extractMethod.invoke(bridge, 'Bearer token ')

    then:
    result1 == null
    result2 == null
    result3 == null
    result4 == 'token'
    result5 == 'token'
  }

  def 'test processJwtToken method processes valid JWT correctly'() {
    given:
    def bridge = new GatewayBridge(Mock(SubscriptionService), Mock(EventProducerService), { Mock(ApiSecuritySampler) } as java.util.function.Supplier, [])
    def processMethod = GatewayBridge.getDeclaredMethod('processJwtToken', AppSecRequestContext, String)
    processMethod.setAccessible(true)
    def appSecCtx = new AppSecRequestContext()
    def validJwt = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c'

    when:
    processMethod.invoke(bridge, appSecCtx, validJwt)

    then:
    appSecCtx.hasAddress(KnownAddresses.REQUEST_JWT)
    def jwt = appSecCtx.get(KnownAddresses.REQUEST_JWT) as Map<String, Object>
    jwt != null
    jwt.containsKey("header")
    jwt.containsKey("payload")
    jwt.containsKey("signature")
  }

  def 'test processJwtToken method handles invalid JWT gracefully'() {
    given:
    def bridge = new GatewayBridge(Mock(SubscriptionService), Mock(EventProducerService), { Mock(ApiSecuritySampler) } as java.util.function.Supplier, [])
    def processMethod = GatewayBridge.getDeclaredMethod('processJwtToken', AppSecRequestContext, String)
    processMethod.setAccessible(true)
    def appSecCtx = new AppSecRequestContext()

    when:
    processMethod.invoke(bridge, appSecCtx, 'invalid.jwt.token')

    then:
    !appSecCtx.hasAddress(KnownAddresses.REQUEST_JWT)
    noExceptionThrown()
  }

  def 'test publishJwtData method publishes data when subscribers exist'() {
    given:
    def eventDispatcher = Mock(EventProducerService)
    def bridge = new GatewayBridge(Mock(SubscriptionService), eventDispatcher, { Mock(ApiSecuritySampler) } as java.util.function.Supplier, [])
    def publishMethod = GatewayBridge.getDeclaredMethod('publishJwtData', AppSecRequestContext, DataBundle)
    publishMethod.setAccessible(true)
    def appSecCtx = new AppSecRequestContext()
    def jwtBundle = com.datadog.appsec.event.data.MapDataBundle.of(KnownAddresses.REQUEST_JWT, [test: 'data'])

    when:
    publishMethod.invoke(bridge, appSecCtx, jwtBundle)

    then:
    1 * eventDispatcher.getDataSubscribers(KnownAddresses.REQUEST_JWT) >> {
      def info = Stub(EventProducerService.DataSubscriberInfo)
      info.empty >> false
      info
    }
    1 * eventDispatcher.publishDataEvent(_, appSecCtx, jwtBundle, _) >> NoopFlow.INSTANCE
  }

  def 'test publishJwtData method does not publish when no subscribers exist'() {
    given:
    def eventDispatcher = Mock(EventProducerService)
    def bridge = new GatewayBridge(Mock(SubscriptionService), eventDispatcher, { Mock(ApiSecuritySampler) } as java.util.function.Supplier, [])
    def publishMethod = GatewayBridge.getDeclaredMethod('publishJwtData', AppSecRequestContext, DataBundle)
    publishMethod.setAccessible(true)
    def appSecCtx = new AppSecRequestContext()
    def jwtBundle = com.datadog.appsec.event.data.MapDataBundle.of(KnownAddresses.REQUEST_JWT, [test: 'data'])

    when:
    publishMethod.invoke(bridge, appSecCtx, jwtBundle)

    then:
    1 * eventDispatcher.getDataSubscribers(KnownAddresses.REQUEST_JWT) >> {
      def info = Stub(EventProducerService.DataSubscriberInfo)
      info.empty >> true
      info
    }
    0 * eventDispatcher.publishDataEvent(_, _, _, _)
  }

  def 'test JWT extraction follows RFC-6750 Bearer token format'() {
    given:
    def bridge = new GatewayBridge(Mock(SubscriptionService), Mock(EventProducerService), { Mock(ApiSecuritySampler) } as java.util.function.Supplier, [])
    def extractMethod = GatewayBridge.getDeclaredMethod('extractJwtToken', String)
    extractMethod.setAccessible(true)

    // Test cases from RFC-6750 examples
    when:
    def result1 = extractMethod.invoke(bridge, 'Bearer mF_9.B5f-4.1JqM')
    def result2 = extractMethod.invoke(bridge, 'Bearer mF_9.B5f-4.1JqM ')
    def result3 = extractMethod.invoke(bridge, ' Bearer mF_9.B5f-4.1JqM')
    def result4 = extractMethod.invoke(bridge, 'Bearer')

    then:
    result1 == 'mF_9.B5f-4.1JqM'
    result2 == 'mF_9.B5f-4.1JqM'
    result3 == 'mF_9.B5f-4.1JqM' // leading space is trimmed by implementation
    result4 == null
  }

  def 'test JWT extraction is case insensitive for Bearer scheme'() {
    given:
    def bridge = new GatewayBridge(Mock(SubscriptionService), Mock(EventProducerService), { Mock(ApiSecuritySampler) } as java.util.function.Supplier, [])
    def extractMethod = GatewayBridge.getDeclaredMethod('extractJwtToken', String)
    extractMethod.setAccessible(true)

    when:
    def result1 = extractMethod.invoke(bridge, 'Bearer token')
    def result2 = extractMethod.invoke(bridge, 'bearer token')
    def result3 = extractMethod.invoke(bridge, 'BEARER token')
    def result4 = extractMethod.invoke(bridge, 'BeArEr token')

    then:
    result1 == 'token'
    result2 == 'token'
    result3 == 'token'
    result4 == 'token'
  }

  def 'test JWT extraction handles whitespace correctly'() {
    given:
    def bridge = new GatewayBridge(Mock(SubscriptionService), Mock(EventProducerService), { Mock(ApiSecuritySampler) } as java.util.function.Supplier, [])
    def extractMethod = GatewayBridge.getDeclaredMethod('extractJwtToken', String)
    extractMethod.setAccessible(true)

    when:
    def result1 = extractMethod.invoke(bridge, 'Bearer token')
    def result2 = extractMethod.invoke(bridge, 'Bearer  token')
    def result3 = extractMethod.invoke(bridge, 'Bearer token ')
    def result4 = extractMethod.invoke(bridge, ' Bearer token ')
    def result5 = extractMethod.invoke(bridge, '  Bearer  token  ')

    then:
    result1 == 'token'
    result2 == 'token'
    result3 == 'token'
    result4 == 'token'
    result5 == 'token'
  }
}
