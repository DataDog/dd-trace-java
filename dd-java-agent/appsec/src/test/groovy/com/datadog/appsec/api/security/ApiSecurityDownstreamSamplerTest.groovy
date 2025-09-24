package com.datadog.appsec.api.security

import com.datadog.appsec.gateway.AppSecRequestContext
import datadog.trace.test.util.DDSpecification

class ApiSecurityDownstreamSamplerTest extends DDSpecification {

  void 'test noop'() {
    given:
    final ctx = Mock(AppSecRequestContext)
    final sampler = new ApiSecurityDownstreamSampler.NoOp()

    when:
    final initialDecisions = (1..10).collect { sampler.sampleHttpClientRequest(ctx, it)}

    then:
    initialDecisions.every { it == false }

    when:
    final sampled = (1..10).collect { sampler.isSampled(ctx, it)}

    then:
    sampled.every { it == false }
  }

  void 'test sampling algorithm'() {
    given:
    final epsilon = 0.05
    final expectedRate = rate < 0 ? 0.0 : rate > 1 ? 1.0 : rate
    final ctx = Mock(AppSecRequestContext) {
      sampleHttpClientRequest(_ as long) >> true
      isHttpClientRequestSampled(_ as long) >> true
    }
    final sampler = new ApiSecurityDownstreamSamplerImpl(rate)

    when:
    final samples = (1..100).collect { sampler.sampleHttpClientRequest(ctx, it)}

    then:
    final receivedRate = samples.count { it } / samples.size()
    receivedRate.subtract(expectedRate).abs() <= epsilon

    where:
    rate << [-1.0, 0.0, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0, 2.0]
  }
}
