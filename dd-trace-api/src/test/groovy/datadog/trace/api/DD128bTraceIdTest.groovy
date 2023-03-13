package datadog.trace.api

import datadog.trace.test.util.DDSpecification
import spock.lang.Ignore

class DD128bTraceIdTest extends DDSpecification {

  @Ignore("Not implemented yet")  // TODO
  def "convert 128-bit ids from/to String"() {
    when:
    def parsedId = DD128bTraceId.from(stringId)
    def id = DD128bTraceId.from(high, low)

    then:
    id == parsedId
    parsedId.toString() == stringId
    id.toString() == stringId

    where:
    high           | low            | stringId
    Long.MIN_VALUE | Long.MIN_VALUE | "80000000000000008000000000000000"
    Long.MIN_VALUE | 1L             | "80000000000000000000000000000001"
    Long.MIN_VALUE | Long.MAX_VALUE | "80000000000000007fffffffffffffff"
    1L             | Long.MIN_VALUE | "00000000000000018000000000000000"
    1L             | 1L             | "00000000000000010000000000000001"
    1L             | Long.MAX_VALUE | "00000000000000017fffffffffffffff"
    Long.MAX_VALUE | Long.MIN_VALUE | "7fffffffffffffff8000000000000000"
    Long.MAX_VALUE | 1L             | "7fffffffffffffff0000000000000001"
    Long.MAX_VALUE | Long.MAX_VALUE | "7fffffffffffffff7fffffffffffffff"
  }
}
