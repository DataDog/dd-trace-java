package datadog.trace.core.datastreams

import datadog.trace.core.test.DDCoreSpecification

class SchemaSamplerTest  extends DDCoreSpecification {

  def "schema sampler samples with correct weights"() {
    given:
    long currentTimeMillis = 100000
    SchemaSampler sampler = new SchemaSampler()

    when:
    int weight1 = sampler.shouldSample(currentTimeMillis)
    int weight2 = sampler.shouldSample(currentTimeMillis + 1000)
    int weight3 = sampler.shouldSample(currentTimeMillis + 2000)
    int weight4 = sampler.shouldSample(currentTimeMillis + 30000)
    int weight5 = sampler.shouldSample(currentTimeMillis + 30001)

    then:
    weight1 == 1
    weight2 == 0
    weight3 == 0
    weight4 == 3
    weight5 == 0
  }
}
