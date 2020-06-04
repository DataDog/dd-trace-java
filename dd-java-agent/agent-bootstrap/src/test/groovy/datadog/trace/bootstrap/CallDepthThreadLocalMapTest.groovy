package datadog.trace.bootstrap

import spock.lang.Specification

class CallDepthThreadLocalMapTest extends Specification {

  def "test CallDepthThreadLocalMap"() {
    setup:
    Class<?> k1 = String
    Class<?> k2 = Integer

    expect:
    CallDepthThreadLocalMap.incrementCallDepth(k1) == 0
    CallDepthThreadLocalMap.incrementCallDepth(k2) == 0

    CallDepthThreadLocalMap.incrementCallDepth(k1) == 1
    CallDepthThreadLocalMap.incrementCallDepth(k2) == 1

    when:
    CallDepthThreadLocalMap.reset(k1)

    then:
    CallDepthThreadLocalMap.incrementCallDepth(k2) == 2

    when:
    CallDepthThreadLocalMap.reset(k2)

    then:
    CallDepthThreadLocalMap.incrementCallDepth(k1) == 0
    CallDepthThreadLocalMap.incrementCallDepth(k2) == 0

    CallDepthThreadLocalMap.incrementCallDepth(k1) == 1
    CallDepthThreadLocalMap.incrementCallDepth(k2) == 1
  }
}
