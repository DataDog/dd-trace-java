package datadog.trace.api

import datadog.trace.test.util.DDSpecification

class W3CTraceParentTest extends DDSpecification {

  def "build produces correct format with samplingPriority=#samplingPriority"() {
    when:
    def result = W3CTraceParent.build(traceId, spanId, samplingPriority)

    then:
    result == expected

    where:
    traceId                                               | spanId              | samplingPriority | expected
    DDTraceId.from(1)                                     | 2                   | 1                | "00-00000000000000000000000000000001-0000000000000002-01"
    DDTraceId.from(1)                                     | 2                   | 0                | "00-00000000000000000000000000000001-0000000000000002-00"
    DDTraceId.from(1)                                     | 2                   | -1               | "00-00000000000000000000000000000001-0000000000000002-00"
    DDTraceId.from(1)                                     | 2                   | 2                | "00-00000000000000000000000000000001-0000000000000002-01"
    DDTraceId.fromHex("0af7651916cd43dd8448eb211c80319c") | 0x00f067aa0ba902b7L | 1                | "00-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-01"
    DDTraceId.from(Long.MAX_VALUE)                        | Long.MAX_VALUE      | 1                | "00-00000000000000007fffffffffffffff-7fffffffffffffff-01"
  }

  def "build matches W3C traceparent format"() {
    when:
    def result = W3CTraceParent.build(DDTraceId.from(123456789L), 987654321L, 1)

    then:
    // W3C format: version-traceId(32 hex)-spanId(16 hex)-flags(2 hex)
    result ==~ /00-[0-9a-f]{32}-[0-9a-f]{16}-(00|01)/
  }
}
