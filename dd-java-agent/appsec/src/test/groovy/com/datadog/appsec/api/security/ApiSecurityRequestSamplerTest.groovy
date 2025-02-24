package com.datadog.appsec.api.security

import com.datadog.appsec.gateway.AppSecRequestContext
import datadog.trace.test.util.DDSpecification

class ApiSecurityRequestSamplerTest extends DDSpecification {

  void 'happy path with single request'() {
    given:
    def ctx = Spy(createContext('route1', 'GET', 200))
    def sampler = new ApiSecurityRequestSampler()

    when:
    sampler.preSampleRequest(ctx)

    then:
    1 * ctx.getRoute()
    1 * ctx.getMethod()
    1 * ctx.getResponseStatus()
    1 * ctx.setKeepOpenForApiSecurityPostProcessing(true)
    1 * ctx.setApiSecurityEndpointHash(_)
    0 * _

    when:
    def sampleDecision = sampler.sampleRequest(ctx)

    then:
    sampleDecision
    _ * ctx.isKeepOpenForApiSecurityPostProcessing() >> true
    1 * ctx.getApiSecurityEndpointHash()
    0 * _
  }

  void 'second request is not sampled for the same endpoint'() {
    Long hash
    given:
    AppSecRequestContext ctx1 = Spy(createContext('route1', 'GET', 200))
    AppSecRequestContext ctx2 = Spy(createContext('route1', 'GET', 200))
    def sampler = new ApiSecurityRequestSampler()

    when:
    sampler.preSampleRequest(ctx1)
    def sampleDecision = sampler.sampleRequest(ctx1)
    sampler.counter.release()

    then:
    sampleDecision
    _ * _

    when:
    sampler.preSampleRequest(ctx2)

    then:
    1 * ctx2.getRoute()
    1 * ctx2.getMethod()
    1 * ctx2.getResponseStatus()
    1 * ctx2.setApiSecurityEndpointHash(_)
    0 * ctx2.setKeepOpenForApiSecurityPostProcessing(_)
    0 * _

    when:
    sampleDecision = sampler.sampleRequest(ctx2)

    then:
    !sampleDecision
    1 * ctx2.getApiSecurityEndpointHash()
    0 * _
  }

  void 'preSampleRequest with maximum concurrent contexts'() {
    given:
    final ctx1 = Spy(createContext('route2', 'GET', 200))
    final ctx2 = Spy(createContext('route3', 'GET', 200))
    final sampler = new ApiSecurityRequestSampler()
    assert sampler.MAX_POST_PROCESSING_TASKS > 0

    when: 'exhaust the maximum number of concurrent contexts'
    for (int i = 0; i < sampler.MAX_POST_PROCESSING_TASKS; i++) {
      sampler.preSampleRequest(createContext('route1', 'GET', 200 + i))
    }

    and: 'try to sample one more'
    sampler.preSampleRequest(ctx1)

    then:
    1 * ctx1.getRoute()
    1 * ctx1.getMethod()
    1 * ctx1.getResponseStatus()
    1 * ctx1.setApiSecurityEndpointHash(_)
    0 * _

    when: 'release one context'
    sampler.counter.release()

    and: 'next can be sampled'
    sampler.preSampleRequest(ctx2)

    then:
    1 * ctx2.getRoute()
    1 * ctx2.getMethod()
    1 * ctx2.getResponseStatus()
    1 * ctx2.setApiSecurityEndpointHash(_)
    1 * ctx2.setKeepOpenForApiSecurityPostProcessing(true)
    0 * _
  }

  void 'preSampleRequest with null route'() {
    given:
    def ctx = Spy(createContext(null, 'GET', 200))
    def sampler = new ApiSecurityRequestSampler()

    when:
    def sampleDecision = sampler.preSampleRequest(ctx)

    then:
    !sampleDecision
    1 * ctx.getRoute()
    0 * _
  }

  void 'preSampleRequest with null method'() {
    given:
    def ctx = Spy(createContext('route1', null, 200))
    def sampler = new ApiSecurityRequestSampler()

    when:
    def sampleDecision = sampler.preSampleRequest(ctx)

    then:
    !sampleDecision
    1 * ctx.getRoute()
    1 * ctx.getMethod()
    0 * _
  }

  void 'preSampleRequest with 0 status code'() {
    given:
    def ctx = Spy(createContext('route1', 'GET', 0))
    def sampler = new ApiSecurityRequestSampler()

    when:
    def sampleDecision = sampler.preSampleRequest(ctx)

    then:
    !sampleDecision
    1 * ctx.getRoute()
    1 * ctx.getMethod()
    1 * ctx.getResponseStatus()
    0 * _
  }

  private AppSecRequestContext createContext(final String route, final String method, int statusCode) {
    final AppSecRequestContext ctx = new AppSecRequestContext()
    ctx.setRoute(route)
    ctx.setMethod(method)
    ctx.setResponseStatus(statusCode)
    ctx
  }

}
