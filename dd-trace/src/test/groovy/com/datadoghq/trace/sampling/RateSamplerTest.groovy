package com.datadoghq.trace.sampling

import spock.lang.Specification

class RateSamplerTest extends Specification {


  def "sampler should let things through under the limit"() {
    setup:
    def sampler = new RateSampler(rate)
    def sleep = ((1 / rate) * 1000).longValue()

    expect:
    for (int i = 0; i < rate; i++) {
      assert sampler.doSample(null)
      if (i + 1 < rate) {
        Thread.sleep(sleep)
      }
    }

    where:
    rate << [1, 5, 50]
  }

  def "sampler should reject things too fast"() {
    setup:
    def sampler = new RateSampler(rate)

    expect:
    sampler.doSample(null) // First passes
    !sampler.doSample(null) // afterwards fails
    !sampler.doSample(null)
    !sampler.doSample(null)

    where:
    rate = 1
  }
}
