package datadog.trace.api

import datadog.trace.bootstrap.instrumentation.api.DummyLambdaContext
import spock.lang.Specification

class DummyLambdaContextTest extends Specification {

  def "test constructor and getters"(){
    when:
    def dummyLambdaContext = new DummyLambdaContext("1234", "5678", "1")
    then:
    dummyLambdaContext.getTraceId().toString() == "1234"
    dummyLambdaContext.getSpanId().toString() == "5678"
    dummyLambdaContext.getSamplingPriority() == 1
  }

  def "test unable to parse sampling priority"(){
    when:
    def dummyLambdaContext = new DummyLambdaContext("1234", "5678", "xxx")
    then:
    dummyLambdaContext.getSamplingPriority() == -128
  }
}
