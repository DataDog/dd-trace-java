package com.datadog.appsec.api.security

import com.datadog.appsec.gateway.AppSecRequestContext
import datadog.trace.test.util.DDSpecification

class ApiSecurityDownstreamSamplerTest extends DDSpecification {

  void 'test include all/none'() {
    given:
    final ctx = Mock(AppSecRequestContext)
    final sampler = ApiSecurityDownstreamSampler.build(rate)

    when:
    final initialDecisions = (1..10).collect { sampler.sampleHttpClientRequest(ctx, it)}

    then:
    initialDecisions.every { it == expected }

    when:
    final sampled = (1..10).collect { sampler.isSampled(ctx, it)}

    then:
    sampled.every { it == expected }

    where:
    rate | expected
    0D   | false
    1D   | true
  }

  void 'test sampling algorithm'() {
    given:
    final epsilon = 0.05
    final ctx = Mock(AppSecRequestContext) {
      sampleHttpClientRequest(_ as long) >> true
      isHttpClientRequestSampled(_ as long) >> true
    }
    final sampler = ApiSecurityDownstreamSampler.build(expectedRate)

    when:
    final samples = (1..100).collect { sampler.sampleHttpClientRequest(ctx, it)}

    then:
    final rate = samples.count { it } / samples.size()
    rate.subtract(expectedRate).abs() <= epsilon

    where:
    expectedRate << [0.1, 0.25, 0.5, 0.75, 0.9]
  }
}
