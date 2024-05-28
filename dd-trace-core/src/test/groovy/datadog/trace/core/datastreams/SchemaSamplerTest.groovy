package datadog.trace.core.datastreams

import datadog.trace.core.test.DDCoreSpecification

class SchemaSamplerTest  extends DDCoreSpecification {

  def "schema sampler samples with correct weights"() {
    given:
    long currentTimeMillis = 100000
    SchemaSampler sampler = new SchemaSampler()

    when:
    boolean canSample1 = sampler.canSample(currentTimeMillis)
    int weight1 = sampler.trySample(currentTimeMillis)
    boolean canSample2= sampler.canSample(currentTimeMillis + 1000)
    int weight2 = sampler.trySample(currentTimeMillis + 1000)
    boolean canSample3 = sampler.canSample(currentTimeMillis + 2000)
    int weight3 = sampler.trySample(currentTimeMillis + 2000)
    boolean canSample4 = sampler.canSample(currentTimeMillis + 30000)
    int weight4 = sampler.trySample(currentTimeMillis + 30000)
    boolean canSample5 = sampler.canSample(currentTimeMillis + 30001)
    int weight5 = sampler.trySample(currentTimeMillis + 30001)

    then:
    canSample1
    weight1 == 1
    !canSample2
    weight2 == 0
    !canSample3
    weight3 == 0
    canSample4
    weight4 == 3
    !canSample5
    weight5 == 0
  }
}
