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

  void 'preSampleRequest with null route and no URL'() {
    given:
    def ctx = createContext(null, 'GET', 200)
    def sampler = new ApiSecuritySamplerImpl()

    when:
    def preSampled = sampler.preSampleRequest(ctx)

    then:
    !preSampled
  }

  void 'preSampleRequest with null route but valid URL uses endpoint fallback'() {
    given:
    def ctx = createContextWithUrl(null, 'GET', 200, 'http://localhost:8080/api/users/123')
    def sampler = new ApiSecuritySamplerImpl()

    when:
    def preSampled = sampler.preSampleRequest(ctx)

    then:
    preSampled
    ctx.getOrComputeEndpoint() != null
    ctx.getApiSecurityEndpointHash() != null
  }

  void 'preSampleRequest with null route and 404 status does not sample'() {
    given:
    def ctx = createContextWithUrl(null, 'GET', 404, 'http://localhost:8080/unknown/path')
    def sampler = new ApiSecuritySamplerImpl()

    when:
    def preSampled = sampler.preSampleRequest(ctx)

    then:
    !preSampled
  }

  void 'preSampleRequest with null route and blocked request does not sample'() {
    given:
    def ctx = createContextWithUrl(null, 'GET', 403, 'http://localhost:8080/admin/users')
    ctx.setWafBlocked()  // Request was blocked by AppSec
    def sampler = new ApiSecuritySamplerImpl()

    when:
    def preSampled = sampler.preSampleRequest(ctx)

    then:
    !preSampled  // Blocked requests should not be sampled
  }

  void 'preSampleRequest with null route and 403 non-blocked API does sample'() {
    given:
    def ctx = createContextWithUrl(null, 'GET', 403, 'http://localhost:8080/api/forbidden-resource')
    // NOT calling setWafBlocked() - this is a legitimate API that returns 403
    def sampler = new ApiSecuritySamplerImpl()

    when:
    def preSampled = sampler.preSampleRequest(ctx)

    then:
    preSampled  // Legitimate APIs that return 403 should be sampled
    ctx.getOrComputeEndpoint() != null
    ctx.getApiSecurityEndpointHash() != null
  }

  void 'preSampleRequest with null route and blocked request with different status codes does not sample'() {
    given:
    def ctx200 = createContextWithUrl(null, 'GET', 200, 'http://localhost:8080/attack')
    ctx200.setWafBlocked()
    def ctx500 = createContextWithUrl(null, 'GET', 500, 'http://localhost:8080/attack')
    ctx500.setWafBlocked()
    def sampler = new ApiSecuritySamplerImpl()

    when:
    def preSampled200 = sampler.preSampleRequest(ctx200)
    def preSampled500 = sampler.preSampleRequest(ctx500)

    then:
    !preSampled200  // Blocked requests should not be sampled regardless of status code
    !preSampled500
  }

  void 'second request with same endpoint is not sampled'() {
    given:
    def ctx1 = createContextWithUrl(null, 'GET', 200, 'http://localhost:8080/api/users/123')
    def ctx2 = createContextWithUrl(null, 'GET', 200, 'http://localhost:8080/api/users/456')
    def sampler = new ApiSecuritySamplerImpl()

    when:
    def preSampled1 = sampler.preSampleRequest(ctx1)
    ctx1.setKeepOpenForApiSecurityPostProcessing(true)
    def sampled1 = sampler.sampleRequest(ctx1)
    sampler.releaseOne()

    then:
    preSampled1
    sampled1

    when:
    def preSampled2 = sampler.preSampleRequest(ctx2)

    then:
    !preSampled2 // Same endpoint pattern, so not sampled
  }

  void 'endpoint is computed only once'() {
    given:
    def ctx = createContextWithUrl(null, 'GET', 200, 'http://localhost:8080/api/users/123')

    when:
    def endpoint1 = ctx.getOrComputeEndpoint()
    def endpoint2 = ctx.getOrComputeEndpoint()

    then:
    endpoint1 != null
    endpoint1 == endpoint2
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

  void 'sampleRequest honors expiration'() {
    given:
    def ctx = createContext('route1', 'GET', 200)
    ctx.setApiSecurityEndpointHash(42L)
    ctx.setKeepOpenForApiSecurityPostProcessing(true)
    final timeSource = new ControllableTimeSource()
    timeSource.set(0)
    final long expirationTimeInMs = 10L
    final long expirationTimeInNs = expirationTimeInMs * 1_000_000
    def sampler = new ApiSecuritySamplerImpl(10, expirationTimeInMs, timeSource)

    when:
    def sampled = sampler.sampleRequest(ctx)

    then:
    sampled

    when:
    sampled = sampler.sampleRequest(ctx)

    then: 'second request is not sampled'
    !sampled

    when: 'expiration time has passed'
    timeSource.advance(expirationTimeInNs)
    sampled = sampler.sampleRequest(ctx)

    then: 'request is sampled again'
    sampled
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
      final ctx = createContext('route1', 'GET', 200 + 1)
      ctx.setApiSecurityEndpointHash(i as long)
      ctx.setKeepOpenForApiSecurityPostProcessing(true)
      assert sampler.sampleRequest(ctx)
      assert sampler.accessMap.size() <= 2
      if (i % 2) {
        timeSource.advance(expirationTimeInMs * 1_000_000)
      }
    }
  }

  void 'preSampleRequest with tracing disabled updates access map immediately'() {
    given:
    injectSysConfig('dd.apm.tracing.enabled', 'false')
    rebuildConfig()
    def ctx = createContext('route1', 'GET', 200)
    final timeSource = new ControllableTimeSource()
    timeSource.set(0)
    final long expirationTimeInMs = 10_000
    def sampler = new ApiSecuritySamplerImpl(10, expirationTimeInMs, timeSource)

    when: 'first request is presampled with tracing disabled'
    def preSampled = sampler.preSampleRequest(ctx)

    then: 'request is sampled and access map is updated immediately'
    preSampled
    sampler.accessMap.size() == 1
    sampler.accessMap.containsKey(ctx.getApiSecurityEndpointHash())

    when: 'second request for same endpoint is attempted'
    def ctx2 = createContext('route1', 'GET', 200)
    sampler.releaseOne()
    def preSampled2 = sampler.preSampleRequest(ctx2)

    then: 'second request is not sampled because endpoint was already updated in first preSampleRequest'
    !preSampled2
  }

  void 'sampleRequest with tracing disabled returns true without updating access map'() {
    given:
    injectSysConfig('dd.apm.tracing.enabled', 'false')
    rebuildConfig()
    def ctx = createContext('route1', 'GET', 200)
    ctx.setApiSecurityEndpointHash(42L)
    ctx.setKeepOpenForApiSecurityPostProcessing(true)
    final timeSource = new ControllableTimeSource()
    timeSource.set(0)
    final long expirationTimeInMs = 10_000
    def sampler = new ApiSecuritySamplerImpl(10, expirationTimeInMs, timeSource)

    when: 'sampleRequest is called with tracing disabled'
    def sampled = sampler.sampleRequest(ctx)

    then: 'request is sampled without updating access map'
    sampled
    sampler.accessMap.size() == 0
  }

  void 'preSampleRequest with tracing enabled does not update access map immediately'() {
    given:
    injectSysConfig('dd.apm.tracing.enabled', 'true')
    rebuildConfig()
    def ctx = createContext('route1', 'GET', 200)
    final timeSource = new ControllableTimeSource()
    timeSource.set(0)
    final long expirationTimeInMs = 10_000
    def sampler = new ApiSecuritySamplerImpl(10, expirationTimeInMs, timeSource)

    when: 'request is presampled with tracing enabled'
    def preSampled = sampler.preSampleRequest(ctx)

    then: 'request is sampled but access map is NOT updated yet'
    preSampled
    sampler.accessMap.size() == 0

    when: 'sampleRequest is called to finalize sampling'
    def sampled = sampler.sampleRequest(ctx)

    then: 'access map is updated in sampleRequest'
    sampled
    sampler.accessMap.size() == 1
    sampler.accessMap.containsKey(ctx.getApiSecurityEndpointHash())
  }

  void 'sampleRequest with tracing enabled updates access map'() {
    given:
    injectSysConfig('dd.apm.tracing.enabled', 'true')
    rebuildConfig()
    def ctx = createContext('route1', 'GET', 200)
    ctx.setApiSecurityEndpointHash(42L)
    ctx.setKeepOpenForApiSecurityPostProcessing(true)
    final timeSource = new ControllableTimeSource()
    timeSource.set(0)
    final long expirationTimeInMs = 10_000
    def sampler = new ApiSecuritySamplerImpl(10, expirationTimeInMs, timeSource)

    when: 'sampleRequest is called with tracing enabled'
    def sampled = sampler.sampleRequest(ctx)

    then: 'request is sampled and access map is updated'
    sampled
    sampler.accessMap.size() == 1
    sampler.accessMap.containsKey(42L)

    when: 'second request for same endpoint is made'
    def sampled2 = sampler.sampleRequest(ctx)

    then: 'second request is not sampled'
    !sampled2
  }

  void 'concurrent requests with tracing disabled do not see expired state'() {
    given:
    injectSysConfig('dd.apm.tracing.enabled', 'false')
    rebuildConfig()
    def ctx1 = createContext('route1', 'GET', 200)
    def ctx2 = createContext('route1', 'GET', 200)
    final timeSource = new ControllableTimeSource()
    timeSource.set(0)
    final long expirationTimeInMs = 10_000
    def sampler = new ApiSecuritySamplerImpl(10, expirationTimeInMs, timeSource)

    when: 'first request is presampled'
    def preSampled1 = sampler.preSampleRequest(ctx1)

    then: 'first request is sampled and access map is updated immediately'
    preSampled1
    ctx1.getApiSecurityEndpointHash() != null
    sampler.accessMap.size() == 1

    when: 'concurrent second request tries to presample same endpoint'
    sampler.releaseOne()
    def preSampled2 = sampler.preSampleRequest(ctx2)

    then: 'second request is not sampled because endpoint is already in access map'
    !preSampled2
  }

  void 'full flow with tracing disabled updates map only in preSampleRequest'() {
    given:
    injectSysConfig('dd.apm.tracing.enabled', 'false')
    rebuildConfig()
    def ctx = createContext('route1', 'GET', 200)
    final timeSource = new ControllableTimeSource()
    timeSource.set(0)
    final long expirationTimeInMs = 10_000
    def sampler = new ApiSecuritySamplerImpl(10, expirationTimeInMs, timeSource)

    when: 'request goes through full sampling flow with tracing disabled'
    def preSampled = sampler.preSampleRequest(ctx)

    then: 'preSampleRequest returns true and updates access map'
    preSampled
    sampler.accessMap.size() == 1
    def hash = ctx.getApiSecurityEndpointHash()
    sampler.accessMap.containsKey(hash)

    when: 'sampleRequest is called'
    def sampled = sampler.sampleRequest(ctx)

    then: 'sampleRequest returns true without modifying access map'
    sampled
    sampler.accessMap.size() == 1
    sampler.accessMap.get(hash) == 0L // Still has the value from preSampleRequest
  }

  private static AppSecRequestContext createContext(final String route, final String method, int statusCode) {
    final AppSecRequestContext ctx = new AppSecRequestContext()
    ctx.setRoute(route)
    ctx.setMethod(method)
    ctx.setResponseStatus(statusCode)
    ctx
  }

  private static AppSecRequestContext createContextWithUrl(final String route, final String method, int statusCode, String url) {
    final AppSecRequestContext ctx = new AppSecRequestContext()
    ctx.setRoute(route)
    ctx.setMethod(method)
    ctx.setResponseStatus(statusCode)
    ctx.setHttpUrl(url)
    ctx
  }
}
