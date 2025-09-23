package datadog.trace.core.propagation

import datadog.trace.api.Config
import datadog.trace.api.DDSpanId
import datadog.trace.api.DynamicConfig
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX
import static datadog.trace.core.propagation.B3HttpCodec.B3_KEY
import static datadog.trace.core.propagation.B3HttpCodec.SAMPLING_PRIORITY_KEY
import static datadog.trace.core.propagation.B3HttpCodec.SPAN_ID_KEY
import static datadog.trace.core.propagation.B3HttpCodec.TRACE_ID_KEY

class B3HttpExtractorTest extends DDSpecification {

  DynamicConfig dynamicConfig
  HttpCodec.Extractor extractor
  boolean origAppSecActive

  void setup() {
    dynamicConfig = DynamicConfig.create()
      .setHeaderTags(["SOME_HEADER": "some-tag"])
      .setBaggageMapping([:])
      .apply()
    extractor = B3HttpCodec.newExtractor(Config.get(), { dynamicConfig.captureTraceConfig() })
    origAppSecActive = ActiveSubsystems.APPSEC_ACTIVE
    ActiveSubsystems.APPSEC_ACTIVE = true

    injectSysConfig(PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED, "true")
  }

  void cleanup() {
    ActiveSubsystems.APPSEC_ACTIVE = origAppSecActive
  }

  def "extract http headers"() {
    setup:
    def traceIdHex = traceId.toString(16).toLowerCase()
    def headers = [
      ""                          : "empty key",
      (TRACE_ID_KEY.toUpperCase()): traceIdHex,
      (SPAN_ID_KEY.toUpperCase()) : spanId.toString(16).toLowerCase(),
      SOME_HEADER                 : "my-interesting-info",
    ]

    if (samplingPriority != null) {
      headers.put(SAMPLING_PRIORITY_KEY, "$samplingPriority".toString())
    }

    when:
    final ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context.traceId == B3TraceId.fromHex(traceIdHex)
    context.spanId == DDSpanId.from("$spanId")
    context.baggage == [:]
    context.tags == [
      "b3.traceid": context.traceId.original,
      "b3.spanid" : DDSpanId.toHexString(context.spanId),
      "some-tag"  : "my-interesting-info"
    ]
    context.samplingPriority == expectedSamplingPriority
    context.origin == null

    where:
    traceId          | spanId           | samplingPriority | expectedSamplingPriority
    1G               | 2G               | null             | PrioritySampling.UNSET
    2G               | 3G               | 1                | PrioritySampling.SAMPLER_KEEP
    3G               | 4G               | 0                | PrioritySampling.SAMPLER_DROP
    TRACE_ID_MAX     | TRACE_ID_MAX - 1 | 0                | PrioritySampling.SAMPLER_DROP
    TRACE_ID_MAX - 1 | TRACE_ID_MAX     | 1                | PrioritySampling.SAMPLER_KEEP
  }

  def "extract http headers with b3 header at the beginning"() {
    setup:
    def headers = [
      ""                          : "empty key",
      (B3_KEY)                    : b3,
      (TRACE_ID_KEY.toUpperCase()): traceId.toString(16).toLowerCase(),
      (SPAN_ID_KEY.toUpperCase()) : spanId.toString(16).toLowerCase(),
      SOME_HEADER                 : "my-interesting-info",
    ]

    if (samplingPriority != null) {
      headers.put(SAMPLING_PRIORITY_KEY, "$samplingPriority".toString())
    }

    when:
    final ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context.traceId == B3TraceId.fromHex(expectedTraceIdHex)
    context.spanId == DDSpanId.from("$expectedSpanId")
    context.baggage == [:]
    context.tags == [
      "b3.traceid": context.traceId.original,
      "b3.spanid" : DDSpanId.toHexString(context.spanId),
      "some-tag"  : "my-interesting-info"
    ]
    context.samplingPriority == expectedSamplingPriority
    context.origin == null

    where:
    b3      | expectedTraceId | expectedSpanId | expectedSamplingPriority
    "2-3-0" | 2G              | 3G             | PrioritySampling.SAMPLER_DROP
    "2-3"   | 2G              | 3G             | PrioritySampling.UNSET
    "0"     | 1G              | 2G             | PrioritySampling.SAMPLER_KEEP // B3 Multi used instead
    null    | 1G              | 2G             | PrioritySampling.SAMPLER_KEEP // B3 Multi used instead

    traceId = 1G
    expectedTraceIdHex = expectedTraceId.toString(16).toLowerCase()
    spanId = 2G
    samplingPriority = 1
  }

  def "extract http headers with b3 header at the end"() {
    setup:
    def headers = [
      ""                          : "empty key",
      (TRACE_ID_KEY.toUpperCase()): traceId.toString(16).toLowerCase(),
      (SPAN_ID_KEY.toUpperCase()) : spanId.toString(16).toLowerCase(),
      (B3_KEY)                    : b3,
      SOME_HEADER                 : "my-interesting-info",
    ]

    if (samplingPriority != null) {
      headers.put(SAMPLING_PRIORITY_KEY, "$samplingPriority".toString())
    }

    when:
    final TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context.traceId == B3TraceId.fromHex(expectedTraceIdHex)
    context.spanId == DDSpanId.from("$expectedSpanId")
    context.baggageItems().empty
    context.tags == [
      "b3.traceid": context.traceId.original,
      "b3.spanid" : DDSpanId.toHexString(context.spanId),
      "some-tag"  : "my-interesting-info"
    ]
    context.samplingPriority == expectedSamplingPriority
    context.origin == null

    where:
    b3      | expectedTraceId | expectedSpanId | expectedSamplingPriority
    "2-3-0" | 2G              | 3G             | PrioritySampling.SAMPLER_DROP
    "2-3"   | 2G              | 3G             | PrioritySampling.UNSET
    "0"     | 1G              | 2G             | PrioritySampling.SAMPLER_KEEP // B3 Multi used instead
    null    | 1G              | 2G             | PrioritySampling.SAMPLER_KEEP // B3 Multi used instead

    traceId = 1G
    expectedTraceIdHex = expectedTraceId.toString(16).toLowerCase()
    spanId = 2G
    samplingPriority = 1
  }

  def "extract 128 bit id truncates id to 64 bit"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase()): traceId,
      (SPAN_ID_KEY.toUpperCase()) : spanId,
    ]

    when:
    def context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    if (expectedTraceId) {
      assert context instanceof ExtractedContext
      assert context.traceId == expectedTraceId
      assert context.spanId == (expectedSpanId == null ? 0 : expectedSpanId)
      assert context.tags["b3.traceid"] == expectedTraceId.original
      assert context.tags["b3.spanid"] == (expectedSpanId == null ? null : DDSpanId.toHexString(expectedSpanId))
    } else {
      assert context == null || (context instanceof TagContext && !(context instanceof ExtractedContext))
    }

    where:
    traceId                            | spanId             | expectedTraceId                                       | expectedSpanId
    "-1"                               | "1"                | null                                                  | null
    "1"                                | "-1"               | null                                                  | null
    "0"                                | "1"                | null                                                  | null
    "00001"                            | "1"                | B3TraceId.fromHex("00001")                            | DDSpanId.fromHex("00001")
    "463ac35c9f6413ad"                 | "463ac35c9f6413ad" | B3TraceId.fromHex("463ac35c9f6413ad")                 | DDSpanId.from("5060571933882717101")
    "463ac35c9f6413ad48485a3953bb6124" | "1"                | B3TraceId.fromHex("463ac35c9f6413ad48485a3953bb6124") | 1
    "f" * 16                           | "1"                | B3TraceId.fromHex("f" * 16)                           | 1
    "a" * 16 + "f" * 16                | "1"                | B3TraceId.fromHex("a" * 16 + "f" * 16)                | 1
    "1" + "f" * 32                     | "1"                | null                                                  | null
    "0" + "f" * 32                     | "1"                | null                                                  | null
    "1"                                | "f" * 16           | B3TraceId.fromHex("1")                                | DDSpanId.MAX
    "1"                                | "1" + "f" * 16     | null                                                  | null
  }

  def "extract header tags with no propagation"() {
    when:
    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    !(context instanceof ExtractedContext)
    context.getTags() == ["some-tag": "my-interesting-info"]

    where:
    headers                              | _
    [SOME_HEADER: "my-interesting-info"] | _
  }

  def "extract headers with forwarding"() {
    when:
    TagContext context = extractor.extract(tagOnlyCtx, ContextVisitors.stringValuesMap())

    then:
    context != null
    !(context instanceof ExtractedContext)
    context.forwarded == "for=$forwardedIp:$forwardedPort"

    when:
    context = extractor.extract(fullCtx, ContextVisitors.stringValuesMap())

    then:
    context instanceof ExtractedContext
    context.traceId.toLong() == 1
    context.spanId.toLong() == 2
    context.forwarded == "for=$forwardedIp:$forwardedPort"

    where:
    forwardedIp = "1.2.3.4"
    forwardedPort = "1234"
    tagOnlyCtx = [
      "Forwarded" : "for=$forwardedIp:$forwardedPort"
    ]
    fullCtx = [
      (TRACE_ID_KEY.toUpperCase()): 1,
      (SPAN_ID_KEY.toUpperCase()) : 2,
      "Forwarded" : "for=$forwardedIp:$forwardedPort"
    ]
  }

  def "extract headers with x-forwarding"() {
    when:
    TagContext context = extractor.extract(tagOnlyCtx, ContextVisitors.stringValuesMap())

    then:
    context != null
    context instanceof TagContext
    context.XForwardedFor == forwardedIp
    context.XForwardedPort == forwardedPort

    when:
    context = extractor.extract(fullCtx, ContextVisitors.stringValuesMap())

    then:
    context instanceof ExtractedContext
    context.traceId.toLong() == 1
    context.spanId.toLong() == 2
    context.XForwardedFor == forwardedIp
    context.XForwardedPort == forwardedPort

    where:
    forwardedIp = "1.2.3.4"
    forwardedPort = "1234"
    tagOnlyCtx = [
      "X-Forwarded-For" : forwardedIp,
      "X-Forwarded-Port": forwardedPort
    ]
    fullCtx = [
      (TRACE_ID_KEY.toUpperCase()): 1,
      (SPAN_ID_KEY.toUpperCase()) : 2,
      "x-forwarded-for"           : forwardedIp,
      "x-forwarded-port"          : forwardedPort
    ]
  }

  def "extract empty headers returns null"() {
    expect:
    extractor.extract(["ignored-header": "ignored-value"], ContextVisitors.stringValuesMap()) == null
  }

  def "extract http headers with invalid non-numeric ID"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase()): "traceId",
      (SPAN_ID_KEY.toUpperCase()) : "spanId",
      SOME_HEADER                 : "my-interesting-info",
    ]

    when:
    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    !(context instanceof ExtractedContext)
    context.getTags() == ["some-tag": "my-interesting-info"]
  }

  def "extract http headers with out of range span ID"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase()): "0",
      (SPAN_ID_KEY.toUpperCase()) : "-1",
      SOME_HEADER                 : "my-interesting-info",
    ]

    when:
    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    !(context instanceof ExtractedContext)
    context.getTags() == ["some-tag": "my-interesting-info"]
  }

  def "extract ids while retaining the original string"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase()): traceId,
      (SPAN_ID_KEY.toUpperCase()) : spanId,
    ]

    when:
    final ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    if (expectedTraceId) {
      assert context.traceId == expectedTraceId
      assert context.traceId.original == traceId
      assert context.spanId == expectedSpanId
      assert DDSpanId.toHexString(context.spanId) == trimmed(spanId)
    } else {
      assert context == null
    }

    where:
    traceId                            | spanId             | expectedSpanId
    "00001"                            | "00001"            | 1
    "463ac35c9f6413ad"                 | "463ac35c9f6413ad" | DDSpanId.from("5060571933882717101")
    "463ac35c9f6413ad48485a3953bb6124" | "1"                | 1
    "f" * 16                           | "1"                | 1
    "a" * 16 + "f" * 16                | "1"                | 1
    "1"                                | "f" * 16           | DDSpanId.MAX
    "1"                                | "000" + "f" * 16   | DDSpanId.MAX

    expectedTraceId = B3TraceId.fromHex(traceId)
  }

  String trimmed(String hex) {
    int length = hex.length()
    int i = 0
    while (i < length  && hex.charAt(i) == '0') {
      i++
    }
    if (i == length) {
      return "0"
    }
    return hex.substring(i, length)
  }

  def "extract common http headers"() {
    setup:
    def headers = [
      (HttpCodec.USER_AGENT_KEY): 'some-user-agent',
      (HttpCodec.X_CLUSTER_CLIENT_IP_KEY): '1.1.1.1',
      (HttpCodec.X_REAL_IP_KEY): '2.2.2.2',
      (HttpCodec.X_CLIENT_IP_KEY): '3.3.3.3',
      (HttpCodec.TRUE_CLIENT_IP_KEY): '4.4.4.4',
      (HttpCodec.FORWARDED_FOR_KEY): '5.5.5.5',
      (HttpCodec.FORWARDED_KEY): '6.6.6.6',
      (HttpCodec.FASTLY_CLIENT_IP_KEY): '7.7.7.7',
      (HttpCodec.CF_CONNECTING_IP_KEY): '8.8.8.8',
      (HttpCodec.CF_CONNECTING_IP_V6_KEY): '9.9.9.9',
    ]

    when:
    final TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    assert context.userAgent == 'some-user-agent'
    assert context.XClusterClientIp == '1.1.1.1'
    assert context.XRealIp == '2.2.2.2'
    assert context.XClientIp == '3.3.3.3'
    assert context.trueClientIp == '4.4.4.4'
    assert context.forwardedFor == '5.5.5.5'
    assert context.forwarded == '6.6.6.6'
    assert context.fastlyClientIp == '7.7.7.7'
    assert context.cfConnectingIp == '8.8.8.8'
    assert context.cfConnectingIpv6 == '9.9.9.9'
  }
}
