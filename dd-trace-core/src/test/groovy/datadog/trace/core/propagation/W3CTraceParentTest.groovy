package datadog.trace.core.propagation

import datadog.trace.api.DDTraceId
import datadog.trace.test.util.DDSpecification

class W3CTraceParentTest extends DDSpecification {

  def "build produces correct format with isSampled=#isSampled"() {
    when:
    def result = W3CTraceParent.from(traceId, spanId, isSampled)

    then:
    result == expected

    where:
    traceId                                               | spanId              | isSampled | expected
    DDTraceId.from(1)                                     | 2                   | true      | "00-00000000000000000000000000000001-0000000000000002-01"
    DDTraceId.from(1)                                     | 2                   | false     | "00-00000000000000000000000000000001-0000000000000002-00"
    DDTraceId.from(1)                                     | 2                   | true      | "00-00000000000000000000000000000001-0000000000000002-01"
    DDTraceId.fromHex("0af7651916cd43dd8448eb211c80319c") | 0x00f067aa0ba902b7L | true      | "00-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-01"
    DDTraceId.from(Long.MAX_VALUE)                        | Long.MAX_VALUE      | true      | "00-00000000000000007fffffffffffffff-7fffffffffffffff-01"
  }

  def "build matches W3C traceparent format"() {
    when:
    def result = W3CTraceParent.from(DDTraceId.from(123456789L), 987654321L, true)

    then:
    // W3C format: version-traceId(32 hex)-spanId(16 hex)-flags(2 hex)
    result ==~ /00-[0-9a-f]{32}-[0-9a-f]{16}-(00|01)/
  }
}
