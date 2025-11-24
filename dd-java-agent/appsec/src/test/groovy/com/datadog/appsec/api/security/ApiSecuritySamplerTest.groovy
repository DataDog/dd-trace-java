package com.datadog.appsec.api.security

import com.datadog.appsec.gateway.AppSecRequestContext
import datadog.trace.api.time.ControllableTimeSource
import datadog.trace.test.util.DDSpecification

class ApiSecuritySamplerTest extends DDSpecification {

  void 'happy path with single request'() {
    given:
    final ctx = createContext('route1', 'GET', 200)
    final sampler = new ApiSecuritySamplerImpl()

    when:
    final preSampled = sampler.preSampleRequest(ctx)

    then:
    preSampled

    when:
    ctx.setKeepOpenForApiSecurityPostProcessing(true)
    final sampled = sampler.sampleRequest(ctx)

    then:
    sampled
  }

  void 'second request is not sampled for the same endpoint'() {
    given:
    AppSecRequestContext ctx1 = createContext('route1', 'GET', 200)
    AppSecRequestContext ctx2 = createContext('route1', 'GET', 200)
    final sampler = new ApiSecuritySamplerImpl()

    when:
    final preSampled1 = sampler.preSampleRequest(ctx1)
    ctx1.setKeepOpenForApiSecurityPostProcessing(true)
    final sampled1 = sampler.sampleRequest(ctx1)
    sampler.releaseOne()

    then:
    preSampled1
    sampled1

    when:
    final preSampled2 = sampler.preSampleRequest(ctx2)

    then:
    !preSampled2
  }

  void 'preSampleRequest with maximum concurrent contexts'() {
    given:
    final ctx1 = Spy(createContext('route2', 'GET', 200))
    final ctx2 = Spy(createContext('route3', 'GET', 200))
    final sampler = new ApiSecuritySamplerImpl()
    assert sampler.MAX_POST_PROCESSING_TASKS > 0

    when: 'exhaust the maximum number of concurrent contexts'
    final List<Boolean> preSampled1 = (1..sampler.MAX_POST_PROCESSING_TASKS).collect {
      sampler.preSampleRequest(createContext('route1', 'GET', 200 + it))
    }

    then:
    preSampled1.every { it }

    and: 'try to sample one more'
    final preSampled2 = sampler.preSampleRequest(ctx1)

    then:
    !preSampled2

    when: 'release one context'
    sampler.releaseOne()

    and: 'next can be sampled'
    final preSampled3 = sampler.preSampleRequest(ctx2)

    then:
    preSampled3
  }

  void 'preSampleRequest with null route'() {
    given:
    def ctx = createContext(null, 'GET', 200)
    def sampler = new ApiSecuritySamplerImpl()

    when:
    def preSampled = sampler.preSampleRequest(ctx)

    then:
    !preSampled
  }

  void 'preSampleRequest with null method'() {
    given:
    def ctx = createContext('route1', null, 200)
    def sampler = new ApiSecuritySamplerImpl()

    when:
    def preSampled = sampler.preSampleRequest(ctx)

    then:
    !preSampled
  }

  void 'preSampleRequest with 0 status code'() {
    given:
    def ctx = createContext('route1', 'GET', 0)
    def sampler = new ApiSecuritySamplerImpl()

    when:
    def preSampled = sampler.preSampleRequest(ctx)

    then:
    !preSampled
  }

  void 'sampleRequest with null context throws'() {
    given:
    def sampler = new ApiSecuritySamplerImpl()

    when:
    sampler.preSampleRequest(null)

    then:
    thrown(NullPointerException)
  }

  void 'sampleRequest without prior preSampleRequest never works'() {
    given:
    def sampler = new ApiSecuritySamplerImpl()
    def ctx = createContext('route1', 'GET', 200)

    when:
    def sampled = sampler.sampleRequest(ctx)

    then:
    !sampled
  }

  void 'preSampleRequest honors expiration'() {
    given:
    def ctx1 = createContext('route1', 'GET', 200)
    def ctx2 = createContext('route1', 'GET', 200)
    def ctx3 = createContext('route1', 'GET', 200)
    final timeSource = new ControllableTimeSource()
    timeSource.set(0)
    final long expirationTimeInMs = 10L
    final long expirationTimeInNs = expirationTimeInMs * 1_000_000
    def sampler = new ApiSecuritySamplerImpl(10, expirationTimeInMs, timeSource)

    when: 'first request samples'
    def preSampled1 = sampler.preSampleRequest(ctx1)
    def sampled1 = sampler.sampleRequest(ctx1)

    then:
    preSampled1
    sampled1

    when: 'second request to same endpoint before expiration'
    def preSampled2 = sampler.preSampleRequest(ctx2)

    then: 'second request is not sampled'
    !preSampled2

    when: 'expiration time has passed'
    sampler.releaseOne()
    timeSource.advance(expirationTimeInNs)
    def preSampled3 = sampler.preSampleRequest(ctx3)
    def sampled3 = sampler.sampleRequest(ctx3)

    then: 'request is sampled again'
    preSampled3
    sampled3
  }

  void 'internal accessMap never goes beyond capacity'() {
    given:
    final timeSource = new ControllableTimeSource()
    timeSource.set(0)
    final long expirationTimeInMs = 10_000
    final int maxCapacity = 10
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl(10, expirationTimeInMs, timeSource)

    expect:
    for (int i = 0; i < maxCapacity * 10; i++) {
      timeSource.advance(1_000_000)
      final ctx = createContext('route1', 'GET', 200 + 1)
      ctx.setApiSecurityEndpointHash(i as long)
      ctx.setKeepOpenForApiSecurityPostProcessing(true)
      assert sampler.sampleRequest(ctx)
      assert sampler.accessMap.size() <= maxCapacity
    }
  }

  void 'expired entries are purged from internal accessMap'() {
    given:
    final timeSource = new ControllableTimeSource()
    timeSource.set(0)
    final long expirationTimeInMs = 10_000
    final int maxCapacity = 10
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl(10, expirationTimeInMs, timeSource)

    expect:
    for (int i = 0; i < maxCapacity * 10; i++) {
      final ctx = createContext('route1', 'GET', 200 + i)
      def preSampled = sampler.preSampleRequest(ctx)
      // First request always samples, then we advance time so each subsequent request expires
      assert preSampled
      def sampled = sampler.sampleRequest(ctx)
      assert sampled
      sampler.releaseOne()
      assert sampler.accessMap.size() <= 2
      if (i % 2) {
        timeSource.advance(expirationTimeInMs * 1_000_000)
      }
    }
  }

  private static AppSecRequestContext createContext(final String route, final String method, int statusCode) {
    final AppSecRequestContext ctx = new AppSecRequestContext()
    ctx.setRoute(route)
    ctx.setMethod(method)
    ctx.setResponseStatus(statusCode)
    ctx
  }
}
