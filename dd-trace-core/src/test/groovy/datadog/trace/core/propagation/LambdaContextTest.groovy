package datadog.trace.core.propagation

import datadog.trace.test.util.DDSpecification

class LambdaContextTest extends DDSpecification {

  def "test constructor"(){
    when:
    def dummyLambdaContext = new LambdaContext("1234", "5678", 1)
    then:
    dummyLambdaContext.getTraceId().toString() == "1234"
    dummyLambdaContext.getSpanId().toString() == "5678"
    dummyLambdaContext.getSamplingPriority() == 1
  }
}
