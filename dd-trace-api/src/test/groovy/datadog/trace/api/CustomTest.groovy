package datadog.trace.api

import datadog.trace.test.util.DDSpecification

class CustomTest extends DDSpecification {

  def "my custom test"() {
    when:
    final ddid = DDSpanId.from(stringId)

    then:
    ddid == expectedId
    DDSpanId.toString(ddid) == stringId

    where:
    stringId                                        | expectedId
    "0"                                             | 0
    "1"                                             | 1
    "18446744073709551615"                          | DDSpanId.MAX
    "${Long.MAX_VALUE}"                             | Long.MAX_VALUE
    "${BigInteger.valueOf(Long.MAX_VALUE).plus(1)}" | Long.MIN_VALUE
  }
}