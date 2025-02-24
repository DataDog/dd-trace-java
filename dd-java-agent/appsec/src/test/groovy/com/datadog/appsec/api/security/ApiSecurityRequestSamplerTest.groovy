package com.datadog.appsec.api.security

import com.datadog.appsec.gateway.AppSecRequestContext
import datadog.trace.test.util.DDSpecification

class ApiSecurityRequestSamplerTest extends DDSpecification {

  void 'happy path with single request'() {
    given:
    def ctx = Mock(AppSecRequestContext)
    def sampler = new ApiSecurityRequestSampler()

    when:
    sampler.preSampleRequest(ctx)

    then:
    _ * ctx.getRoute() >> 'route1'
    _ * ctx.getMethod() >> 'GET'
    _ * ctx.getResponseStatus() >> 200
    1 * ctx.setKeepOpenForApiSecurityPostProcessing(true)
    0 * _

    when:
    def sampleDecision = sampler.sampleRequest(ctx)

    then:
    sampleDecision
    _ * ctx.getRoute() >> 'route1'
    _ * ctx.getMethod() >> 'GET'
    _ * ctx.getResponseStatus() >> 200
    _ * ctx.isKeepOpenForApiSecurityPostProcessing() >> true
    0 * _
  }

  void 'second request is not sampled for the same endpoint'() {
    given:
    AppSecRequestContext ctx1 = Mock(AppSecRequestContext)
    AppSecRequestContext ctx2 = Mock(AppSecRequestContext)
    def sampler = new ApiSecurityRequestSampler()

    when:
    sampler.preSampleRequest(ctx1)
    def sampleDecision = sampler.sampleRequest(ctx1)

    then:
    sampleDecision
    _ * ctx1.getRoute() >> 'route1'
    _ * ctx1.getMethod() >> 'GET'
    _ * ctx1.getResponseStatus() >> 200
    _ * _

    when:
    sampler.preSampleRequest(ctx2)

    then:
    _ * ctx2.getRoute() >> 'route1'
    _ * ctx2.getMethod() >> 'GET'
    _ * ctx2.getResponseStatus() >> 200
    0 * ctx2.setKeepOpenForApiSecurityPostProcessing(_)
    0 * _

    when:
    sampleDecision = sampler.sampleRequest(ctx2)

    then:
    !sampleDecision
    _ * ctx2.getRoute() >> 'route1'
    _ * ctx2.getMethod() >> 'GET'
    _ * ctx2.getResponseStatus() >> 200
    0 * _
  }

}
